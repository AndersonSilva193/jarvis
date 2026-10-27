package dev.jarvis.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.OperacoesPcService.RotinaPublica;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O último catálogo que o PC anunciou: os apps que ele aceita abrir e as rotinas que ele sabe rodar.
 *
 * <p><b>Por que isto existe em disco.</b> As duas listas vão para a {@code descricao()} e para o
 * {@code esquemaEntrada()} de {@code controlar_pc} e {@code rodar_rotina} — ou seja, para o array
 * {@code tools} da Messages API, que é renderizado <b>antes</b> do {@code system}. Qualquer byte
 * diferente ali invalida o cache de prompt de {@code tools}, de {@code system} e de
 * {@code messages} de uma vez: é a única linha da hierarquia de invalidação da Anthropic, junto com
 * a troca de modelo, que não tem escapatória. Enquanto as listas viviam só em memória, um restart
 * do container esvaziava as duas e o prefixo inteiro era reconstruído do zero no primeiro pedido —
 * e de novo quando o agente voltasse a anunciar.
 *
 * <p><b>A regra é uma só: a lista muda quando o PC anuncia uma lista diferente.</b> Ficar offline
 * não muda nada (isso nunca esvaziou, e continua não esvaziando); anúncio ausente não muda nada; e
 * anúncio igual ao que já está guardado não grava. Anúncio <b>explicitamente vazio</b>, sim, é uma
 * lista diferente e muda — é a assimetria que {@code PcService.registrarApps} documenta, e ela
 * continua valendo: cabeçalho ausente é falta de informação, cabeçalho vazio é informação.
 *
 * <p>Guarda o arquivo ao lado do {@code operacoes.json}, no volume {@code pc-operacoes} que já
 * existe. Sem configuração nova: é a regra do {@code CacheDeVoz} — pasta derivada, e não mais um
 * componente num record montado por posição em dezenas de testes.
 */
@Component
public class CatalogoDoPc {

    private static final Logger log = LoggerFactory.getLogger(CatalogoDoPc.class);

    /** Teto de sanidade: 30 apps e 30 rotinas higienizadas cabem com folga. */
    private static final long MAX_BYTES = 64_000;

    private final JsonMapper json;
    /** Nulo no modo em memória, que é o dos testes e dos construtores sem Spring. */
    private final Path arquivo;

    private volatile List<String> apps = List.of();
    private volatile List<RotinaPublica> rotinas = List.of();
    private volatile boolean carregado;

    @Autowired
    public CatalogoDoPc(JsonMapper json, JarvisProperties props) {
        this(json, caminho(props));
    }

    CatalogoDoPc(JsonMapper json, Path arquivo) {
        this.json = json;
        this.arquivo = arquivo;
        this.carregado = arquivo == null;
    }

    /** Catálogo que não grava nada, para quem monta os serviços do PC à mão. */
    public static CatalogoDoPc emMemoria() {
        return new CatalogoDoPc(JsonMapper.builder().build(), (Path) null);
    }

    private static Path caminho(JarvisProperties props) {
        String operacoes = props == null || props.pc() == null
                || props.pc().operacoesPasta() == null || props.pc().operacoesPasta().isBlank()
                        ? "/data/pc/operacoes.json" : props.pc().operacoesPasta();
        return Path.of(operacoes).resolveSibling("catalogo.json");
    }

    // ---------------------------------------------------------------- leitura

    public List<String> apps() {
        carregarSePreciso();
        return apps;
    }

    public List<RotinaPublica> rotinas() {
        carregarSePreciso();
        return rotinas;
    }

    // ---------------------------------------------------------------- anúncio

    /**
     * Guarda os apps anunciados, se forem diferentes dos de agora.
     *
     * @param anunciados a lista já higienizada por quem recebeu o cabeçalho, ou {@code null} para
     *                   "não houve anúncio" — que não muda nada
     */
    public void anunciarApps(List<String> anunciados) {
        if (anunciados == null) {
            return;
        }
        carregarSePreciso();
        List<String> nova = List.copyOf(anunciados);
        if (nova.equals(apps)) {
            return;
        }
        apps = nova;
        log.info("PC anunciou {} app(s): {}", nova.size(), nova);
        gravar();
    }

    /**
     * Guarda as rotinas anunciadas, se forem diferentes das de agora.
     *
     * @param anunciadas a lista já validada por {@code OperacoesPcService.catalogo}, ou {@code null}
     *                   para "não houve anúncio"
     */
    public void anunciarRotinas(List<RotinaPublica> anunciadas) {
        if (anunciadas == null) {
            return;
        }
        carregarSePreciso();
        List<RotinaPublica> nova = List.copyOf(anunciadas);
        if (nova.equals(rotinas)) {
            return;
        }
        rotinas = nova;
        log.info("PC anunciou {} rotina(s): {}", nova.size(),
                nova.stream().map(RotinaPublica::nome).toList());
        gravar();
    }

    // ---------------------------------------------------------------- disco

    /**
     * Lê o arquivo na partida, para o prefixo do cache de prompt já nascer igual ao de antes do
     * restart.
     *
     * <p>Fora do construtor de propósito, como o {@code MedidorDeCusto.naPartida()}: I/O num volume
     * Docker durante a criação do bean atrasa a subida do contexto.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void naPartida() {
        carregarSePreciso();
    }

    /**
     * Carrega uma vez, na primeira necessidade.
     *
     * <p>Preguiçoso <b>e</b> na partida porque a ordem entre dois ouvintes de
     * {@code ApplicationReadyEvent} não é garantida: um primeiro pedido que chegasse antes do
     * ouvinte leria as listas vazias e montaria um prefixo que a leitura seguinte mudaria.
     */
    private void carregarSePreciso() {
        if (carregado) {
            return;
        }
        synchronized (this) {
            if (carregado) {
                return;
            }
            carregado = true;
            ler();
        }
    }

    private void ler() {
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            return;
        }
        try {
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
            List<String> lidosApps = new ArrayList<>();
            for (JsonNode no : raiz.path("apps")) {
                String nome = Json.texto(no);
                if (!nome.isBlank()) {
                    lidosApps.add(nome);
                }
            }
            List<RotinaPublica> lidasRotinas = new ArrayList<>();
            for (JsonNode no : raiz.path("rotinas")) {
                String nome = Json.texto(no.path("nome"));
                if (nome.isBlank()) {
                    continue;
                }
                List<String> capacidades = new ArrayList<>();
                for (JsonNode cap : no.path("capacidades")) {
                    String texto = Json.texto(cap);
                    if (!texto.isBlank()) {
                        capacidades.add(texto);
                    }
                }
                lidasRotinas.add(new RotinaPublica(nome, Json.texto(no.path("descricao")),
                        List.copyOf(capacidades)));
            }
            apps = List.copyOf(lidosApps);
            rotinas = List.copyOf(lidasRotinas);
            log.info("Catálogo do PC recuperado: {} app(s), {} rotina(s)", apps.size(),
                    rotinas.size());
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui ler o catálogo do PC em {}: {}", arquivo, e.getMessage());
        }
    }

    /** Uma falha de disco não pode derrubar o long poll do agente, então só vira WARN. */
    private void gravar() {
        if (arquivo == null) {
            return;
        }
        ObjectNode raiz = json.createObjectNode();
        apps.forEach(raiz.putArray("apps")::add);
        var lista = raiz.putArray("rotinas");
        for (RotinaPublica rotina : rotinas) {
            ObjectNode no = lista.addObject();
            no.put("nome", rotina.nome());
            no.put("descricao", rotina.descricao());
            var capacidades = no.putArray("capacidades");
            if (rotina.capacidades() != null) {
                rotina.capacidades().forEach(capacidades::add);
            }
        }
        try {
            ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui gravar o catálogo do PC em {}: {}", arquivo, e.getMessage());
        }
    }
}
