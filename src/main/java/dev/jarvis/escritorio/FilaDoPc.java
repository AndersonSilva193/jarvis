package dev.jarvis.escritorio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.escritorio.EscritaNoPc.Estado;
import dev.jarvis.escritorio.EscritaNoPc.Resposta;
import dev.jarvis.pc.PcVoltou;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.ArquivoAtomico;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que precisa ir para as planilhas do PC e ainda não foi: PC desligado, arquivo aberto no Excel,
 * agente antigo ou resposta que não chegou.
 *
 * <p>Ordem de chegada sempre: um pedido novo não passa na frente de um que está esperando, e o
 * esvaziamento para no primeiro que não grava. A fila mora em {@code DIARIO_PASTA/pendentes-pc.json}
 * e sobrevive ao restart. Esvazia quando o PC volta e a cada cinco minutos.
 *
 * <p>{@link Estado#ID_INEXISTENTE} não se repete: o pedido sai da fila. Num {@code remover_linha}
 * já tentado, é o sinal de que a tentativa anterior removeu sem a resposta chegar.
 */
@Component
public class FilaDoPc {

    private static final Logger log = LoggerFactory.getLogger(FilaDoPc.class);
    static final String ARQUIVO = "pendentes-pc.json";
    private static final long MAX_BYTES = 8L * 1024 * 1024;

    private final Function<PedidoDeEscrita, Resposta> envio;
    private final Path arquivo;
    private final JsonMapper json;
    private final Consumer<String> aviso;
    private final Executor executor;
    private final List<PedidoDeEscrita> fila = new ArrayList<>();
    /** Já avisou que o agente é antigo; volta a avisar depois de um pedido gravado. */
    private boolean avisouAgenteAntigo;

    @Autowired
    public FilaDoPc(EscritaNoPc escrita, JarvisProperties props, JsonMapper json, TelegramClient telegram) {
        this(escrita::enviar, props.pastaDoDiario().resolve(ARQUIVO), json,
                texto -> {
                    if (props.telegram() != null) telegram.enviarMensagem(props.telegram().usuarioPermitido(), texto);
                },
                tarefa -> Thread.ofVirtual().name("fila-do-pc").start(tarefa));
    }

    /** Para os testes e para quem monta à mão: o envio ao PC e o executor vêm de fora. */
    public FilaDoPc(Function<PedidoDeEscrita, Resposta> envio, Path arquivo, JsonMapper json, Consumer<String> aviso,
            Executor executor) {
        this.envio = envio;
        this.arquivo = arquivo;
        this.json = json;
        this.aviso = aviso;
        this.executor = executor;
        carregar();
    }

    /**
     * Grava agora, se der; senão guarda. Devolve o estado do próprio pedido ou, se ele ficou atrás
     * de outro, o estado que segura a fila. {@link Estado#ID_INEXISTENTE} na hora volta para quem
     * pediu e não entra na fila.
     */
    public synchronized Resposta gravarOuEnfileirar(PedidoDeEscrita pedido) {
        if (fila.isEmpty()) {
            Resposta resposta = envio.apply(pedido);
            if (resposta.gravado()) avisouAgenteAntigo = false;
            if (resposta.gravado() || resposta.estado() == Estado.ID_INEXISTENTE) return resposta;
            fila.add(pedido.maisUmaTentativa());
            salvar();
            if (resposta.estado() == Estado.AGENTE_ANTIGO) avisarAgenteAntigo();
            return resposta;
        }
        fila.add(pedido);
        salvar();
        Map<String, Resposta> respostas = new HashMap<>();
        Resposta parou = esvaziarAgora(respostas);
        Resposta propria = respostas.get(pedido.id());
        if (propria != null && (propria.gravado() || propria.estado() == Estado.ID_INEXISTENTE)) return propria;
        return parou != null ? parou : propria;
    }

    /**
     * Só guarda e pede o esvaziamento em outra thread, sem esperar o PC. Para quem não tem a quem
     * responder na hora, como a migração do livro na partida.
     */
    public synchronized void enfileirar(PedidoDeEscrita pedido) {
        fila.add(pedido);
        salvar();
        executor.execute(this::esvaziar);
    }

    public synchronized int pendentes() {
        return fila.size();
    }

    /**
     * Em outra thread: o evento sai de dentro da consulta do agente, e esperar aqui por um resultado
     * que só chega quando essa consulta termina travaria os dois até o tempo acabar.
     */
    @EventListener
    public void pcVoltou(PcVoltou evento) {
        executor.execute(this::esvaziar);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public synchronized void esvaziar() {
        esvaziarAgora(new HashMap<>());
    }

    /**
     * Manda os pedidos em ordem até a fila acabar ou um não gravar.
     *
     * @param respostas recebe a resposta de cada pedido tentado agora
     * @return a resposta que parou a fila, ou nulo se ela esvaziou
     */
    private Resposta esvaziarAgora(Map<String, Resposta> respostas) {
        while (!fila.isEmpty()) {
            PedidoDeEscrita pedido = fila.get(0);
            Resposta resposta = envio.apply(pedido);
            respostas.put(pedido.id(), resposta);
            if (resposta.gravado()) {
                avisouAgenteAntigo = false;
                fila.remove(0);
                salvar();
            } else if (resposta.estado() == Estado.ID_INEXISTENTE) {
                fila.remove(0);
                salvar();
                if (!("remover_linha".equals(pedido.acao()) && pedido.tentativas() > 0))
                    aviso.accept("Não achei a linha que era para " + ("remover_linha".equals(pedido.acao())
                            ? "remover" : "alterar") + " em " + pedido.arquivo()
                            + " no PC; o pedido foi descartado. Confira o arquivo.");
            } else {
                fila.set(0, pedido.maisUmaTentativa());
                salvar();
                if (resposta.estado() == Estado.AGENTE_ANTIGO) avisarAgenteAntigo();
                return resposta;
            }
        }
        return null;
    }

    private void avisarAgenteAntigo() {
        if (avisouAgenteAntigo) return;
        avisouAgenteAntigo = true;
        int quantos = fila.size();
        aviso.accept("Senhor, atualize o agente do PC: ele ainda não sabe gravar na agenda e no livro "
                + "financeiro. " + quantos + (quantos == 1 ? " pedido esperando." : " pedidos esperando."));
    }

    // ---------------------------------------------------------------- disco

    private void carregar() {
        if (!Files.exists(arquivo)) return;
        try {
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
            for (JsonNode item : raiz) {
                if (!item.path("dados").isObject()) continue;
                fila.add(new PedidoDeEscrita(item.path("id").asString(), item.path("acao").asString(),
                        item.path("arquivo").asString(), (ObjectNode) item.path("dados"),
                        item.path("criadoEm").asString(), item.path("tentativas").asInt()));
            }
            if (!fila.isEmpty()) log.info("{} escrita(s) esperando o PC", fila.size());
        } catch (IOException | RuntimeException e) {
            log.error("Não consegui ler {}; a fila do PC começa vazia", arquivo, e);
        }
    }

    /** Falha de disco não perde o pedido: ele segue em memória e a próxima gravação tenta de novo. */
    private void salvar() {
        ArrayNode lista = json.createArrayNode();
        for (PedidoDeEscrita pedido : fila) {
            lista.addObject().put("id", pedido.id()).put("acao", pedido.acao()).put("arquivo", pedido.arquivo())
                    .put("criadoEm", pedido.criadoEm()).put("tentativas", pedido.tentativas())
                    .set("dados", pedido.dados());
        }
        try {
            ArquivoAtomico.gravar(arquivo, json.writeValueAsString(lista));
        } catch (IOException | RuntimeException e) {
            log.error("Não consegui gravar {}; {} pedido(s) só em memória", arquivo, fila.size(), e);
        }
    }
}
