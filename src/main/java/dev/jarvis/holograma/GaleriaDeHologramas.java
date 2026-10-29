package dev.jarvis.holograma;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Texto;
import tools.jackson.databind.json.JsonMapper;

/**
 * Os hologramas já gerados, para reabrir sem gastar modelo: um {@code <id>.json} por objeto e, ao
 * lado, o {@code <id>.png} da miniatura que a HUD capturou. Volume {@code holograma}, com a troca
 * atômica do {@link ArquivoAtomico}.
 *
 * <p><b>Só entra objeto completo</b> e com ao menos {@value #MINIMO_DE_PECAS} peças: incompleto é
 * meio objeto, e um cache dele serviria a metade para sempre. Quem decide é o
 * {@link HologramaService}, no instante em que o {@code fim} completo sai.
 *
 * <p><b>A chave é o pedido, não o nome que o modelo deu</b>: {@link #chave} normaliza o que o dono
 * digitou (sem acento, minúsculo, sem artigo, espaço simples) e soma o nível de detalhe. "o Motor
 * V8" e "motor v8" são o mesmo objeto; o mesmo objeto em outro nível é outro. Um objeto salvo com
 * a chave de outro substitui o anterior — é o "Gerar de novo".
 *
 * <p>Os três exemplos escritos à mão entram na lista como itens <b>fixos</b>: não se apagam e não
 * respondem a pedido nenhum de geração — são vitrine, não cache.
 *
 * <p>Em memória fica só o índice (o que a lista mostra); as peças são lidas do disco ao abrir.
 */
@Component
public class GaleriaDeHologramas {

    private static final Logger log = LoggerFactory.getLogger(GaleriaDeHologramas.class);

    /** Menos que isso não é um objeto que valha reabrir. */
    public static final int MINIMO_DE_PECAS = 8;
    /** Acima disso o mais antigo sai: a galeria é atalho, não arquivo morto. */
    static final int MAX_ITENS = 60;
    /** 250 peças com pontos cabem com folga. */
    private static final long MAX_BYTES_OBJETO = 1_000_000;
    /** Uma miniatura de 256 px em PNG tem dezenas de kB; isto é teto de sanidade. */
    public static final int MAX_BYTES_MINIATURA = 300_000;

    /** Os ids da geração ({@code h} + 8) e os dos exemplos: nada que saia da pasta. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_]{1,40}");
    static final String PREFIXO_EXEMPLO = "exemplo_";
    /** O que o {@code fim} e a lista dizem de quem gerou um exemplo. */
    public static final String PROVEDOR_EXEMPLO = "exemplo";
    private static final Set<String> ARTIGOS = Set.of("o", "a", "os", "as", "um", "uma", "uns", "umas");

    /**
     * Um objeto salvo, inteiro: o que o {@code /stream} reproduz.
     *
     * @param objeto o pedido como chegou (higienizado), que é o que o "Gerar de novo" repete
     * @param criadoEm ISO-8601; vazio nos exemplos
     */
    public record HologramaSalvo(String id, String objeto, String detalhe, String provedor, String modelo,
            String criadoEm, HologramaMeta meta, List<HologramaPeca> pecas) {
    }

    /** Uma linha da galeria, sem as peças. */
    public record Item(String id, String nome, int pecas, String data, String provedor, String detalhe,
            String objeto, boolean fixo, boolean miniatura) {
    }

    public enum Remocao { APAGADO, FIXO, NAO_EXISTE }

    private final JsonMapper json;
    private final Path pasta;
    private final ExemplosDeHolograma exemplos;
    private final HologramaLineParser parser;

    /** id → item; a chave de cada um fica no mapa ao lado. */
    private final Map<String, Item> itens = new LinkedHashMap<>();
    private final Map<String, String> chaves = new LinkedHashMap<>();
    /** Os exemplos montados uma vez, na primeira leitura. */
    private Map<String, HologramaSalvo> fixos;
    private boolean carregado;

    @Autowired
    public GaleriaDeHologramas(JsonMapper json, JarvisProperties props, ExemplosDeHolograma exemplos,
            HologramaLineParser parser) {
        this(json, props.pastaDoHolograma(), exemplos, parser);
    }

    public GaleriaDeHologramas(JsonMapper json, Path pasta, ExemplosDeHolograma exemplos,
            HologramaLineParser parser) {
        this.json = json;
        this.pasta = pasta;
        this.exemplos = exemplos;
        this.parser = parser;
    }

    /**
     * A chave do cache: o pedido sem acento, em minúsculas, sem artigo e com espaço simples, mais o
     * nível. Pedido feito só de artigo ("a") fica com ele, senão viraria a chave vazia.
     */
    public static String chave(String objeto, DetalheHolograma detalhe) {
        String normalizado = Texto.normalizar(objeto);
        String semArtigos = Arrays.stream(normalizado.split(" "))
                .filter(palavra -> !ARTIGOS.contains(palavra))
                .collect(Collectors.joining(" "));
        return (semArtigos.isEmpty() ? normalizado : semArtigos) + "|" + detalhe.chave();
    }

    /** O formato de id que esta galeria aceita: o que o controlador confere antes de procurar. */
    public static boolean idValido(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** O objeto salvo para este pedido, se houver. Exemplo não responde a pedido. */
    public synchronized Optional<HologramaSalvo> buscar(String objeto, DetalheHolograma detalhe) {
        carregarSePreciso();
        String procurada = chave(objeto, detalhe);
        return chaves.entrySet().stream()
                .filter(e -> e.getValue().equals(procurada))
                .findFirst()
                .flatMap(e -> ler(e.getKey()));
    }

    /** O objeto pelo id: um salvo ou um exemplo. */
    public synchronized Optional<HologramaSalvo> abrir(String id) {
        if (!idValido(id)) return Optional.empty();
        carregarSePreciso();
        HologramaSalvo fixo = fixos.get(id);
        if (fixo != null) return Optional.of(fixo);
        return itens.containsKey(id) ? ler(id) : Optional.empty();
    }

    public synchronized boolean contem(String id) {
        carregarSePreciso();
        return fixos.containsKey(id) || itens.containsKey(id);
    }

    /** Os exemplos primeiro, depois os salvos do mais novo para o mais velho. */
    public synchronized List<Item> listar() {
        carregarSePreciso();
        List<Item> lista = new ArrayList<>();
        fixos.values().forEach(fixo -> lista.add(itemDe(fixo, true)));
        itens.values().stream()
                .sorted(Comparator.comparing(Item::data).reversed())
                .map(item -> comMiniatura(item))
                .forEach(lista::add);
        return lista;
    }

    /**
     * Guarda um objeto completo, substituindo o que houver com a mesma chave. Volta {@code false}
     * quando não guardou — poucas peças ou o disco recusou —, e o motivo vai ao log.
     */
    public synchronized boolean salvar(String id, String objeto, DetalheHolograma detalhe, String provedor,
            String modelo, HologramaMeta meta, List<HologramaPeca> pecas) {
        if (!idValido(id) || id.startsWith(PREFIXO_EXEMPLO) || pecas.size() < MINIMO_DE_PECAS) {
            return false;
        }
        carregarSePreciso();
        HologramaSalvo salvo = new HologramaSalvo(id, objeto, detalhe.chave(), provedor, modelo,
                Instant.now().toString(), meta, List.copyOf(pecas));
        try {
            ArquivoAtomico.gravar(arquivoDe(id), json.writeValueAsString(salvo));
        } catch (IOException | RuntimeException e) {
            log.warn("Galeria: não consegui guardar o holograma {}: {}", id, e.toString());
            return false;
        }
        String chave = chave(objeto, detalhe);
        List<String> substituidos = chaves.entrySet().stream()
                .filter(e -> e.getValue().equals(chave) && !e.getKey().equals(id))
                .map(Map.Entry::getKey).toList();
        substituidos.forEach(this::remover);
        indexar(salvo, chave);
        descartarExcedente();
        log.info("Galeria: holograma '{}' guardado como {} ({} peças{})", curto(objeto), id, pecas.size(),
                substituidos.isEmpty() ? "" : ", substituiu " + substituidos);
        return true;
    }

    public synchronized Remocao apagar(String id) {
        if (!idValido(id)) return Remocao.NAO_EXISTE;
        carregarSePreciso();
        if (fixos.containsKey(id)) return Remocao.FIXO;
        if (!itens.containsKey(id)) return Remocao.NAO_EXISTE;
        remover(id);
        log.info("Galeria: holograma {} apagado", id);
        return Remocao.APAGADO;
    }

    /** Grava a miniatura de um objeto que existe (exemplo inclusive). Os bytes já foram conferidos. */
    public synchronized boolean guardarMiniatura(String id, byte[] png) {
        if (!contem(id) || png.length > MAX_BYTES_MINIATURA) return false;
        try {
            Files.createDirectories(pasta);
            Path destino = miniaturaDe(id);
            Path temporario = Files.createTempFile(pasta, ".jarvis-", ".tmp");
            try {
                Files.write(temporario, png);
                Files.move(temporario, destino, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporario);
            }
            return true;
        } catch (IOException e) {
            log.warn("Galeria: não consegui guardar a miniatura de {}: {}", id, e.toString());
            return false;
        }
    }

    public synchronized Optional<byte[]> miniatura(String id) {
        if (!idValido(id)) return Optional.empty();
        Path arquivo = miniaturaDe(id);
        try {
            if (!Files.isRegularFile(arquivo) || Files.size(arquivo) > MAX_BYTES_MINIATURA) return Optional.empty();
            return Optional.of(Files.readAllBytes(arquivo));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public synchronized boolean temMiniatura(String id) {
        return idValido(id) && Files.isRegularFile(miniaturaDe(id));
    }

    // ---------------------------------------------------------------- disco

    private void carregarSePreciso() {
        if (carregado) return;
        carregado = true;
        fixos = montarExemplos();
        if (!Files.isDirectory(pasta)) return;
        try (DirectoryStream<Path> arquivos = Files.newDirectoryStream(pasta, "*.json")) {
            for (Path arquivo : arquivos) {
                String nome = arquivo.getFileName().toString();
                String id = nome.substring(0, nome.length() - ".json".length());
                if (!idValido(id) || id.startsWith(PREFIXO_EXEMPLO)) continue;
                ler(id).ifPresent(salvo -> {
                    DetalheHolograma detalhe = DetalheHolograma.porChave(salvo.detalhe()).orElse(DetalheHolograma.NORMAL);
                    indexar(salvo, chave(salvo.objeto(), detalhe));
                });
            }
        } catch (IOException e) {
            log.warn("Galeria: não consegui listar {}: {}", pasta, e.toString());
        }
        descartarExcedente();
        log.info("Galeria de hologramas: {} objeto(s) em {}", itens.size(), pasta);
    }

    private Optional<HologramaSalvo> ler(String id) {
        Path arquivo = arquivoDe(id);
        try {
            HologramaSalvo salvo = json.readValue(ArquivoAtomico.ler(arquivo, MAX_BYTES_OBJETO), HologramaSalvo.class);
            if (salvo == null || !id.equals(salvo.id()) || salvo.meta() == null || salvo.pecas() == null
                    || salvo.objeto() == null) {
                log.warn("Galeria: {} não é um holograma salvo; ignorado", arquivo.getFileName());
                return Optional.empty();
            }
            return Optional.of(salvo);
        } catch (IOException | RuntimeException e) {
            log.warn("Galeria: não consegui ler {}: {}", arquivo.getFileName(), e.toString());
            return Optional.empty();
        }
    }

    private void indexar(HologramaSalvo salvo, String chave) {
        itens.put(salvo.id(), itemDe(salvo, false));
        chaves.put(salvo.id(), chave);
    }

    private void remover(String id) {
        itens.remove(id);
        chaves.remove(id);
        try {
            ArquivoAtomico.apagar(arquivoDe(id));
            Files.deleteIfExists(miniaturaDe(id));
        } catch (IOException e) {
            log.warn("Galeria: não consegui apagar {}: {}", id, e.toString());
        }
    }

    private void descartarExcedente() {
        while (itens.size() > MAX_ITENS) {
            itens.values().stream().min(Comparator.comparing(Item::data))
                    .ifPresent(maisVelho -> remover(maisVelho.id()));
        }
    }

    private Map<String, HologramaSalvo> montarExemplos() {
        Map<String, HologramaSalvo> mapa = new LinkedHashMap<>();
        for (String nome : ExemplosDeHolograma.NOMES) {
            exemplos.linhas(nome).ifPresent(linhas -> {
                MontagemDeHolograma montagem = new MontagemDeHolograma(parser);
                HologramaMeta meta = HologramaMeta.PADRAO;
                List<HologramaPeca> pecas = new ArrayList<>();
                List<SaidaHolograma> saidas = new ArrayList<>();
                linhas.forEach(linha -> saidas.addAll(montagem.processar(linha)));
                saidas.addAll(montagem.terminar());
                for (SaidaHolograma saida : saidas) {
                    if (saida instanceof SaidaHolograma.Meta m) meta = m.meta();
                    else if (saida instanceof SaidaHolograma.Peca p) pecas.add(p.peca());
                }
                String id = PREFIXO_EXEMPLO + nome;
                mapa.put(id, new HologramaSalvo(id, meta.nome(), DetalheHolograma.NORMAL.chave(),
                        PROVEDOR_EXEMPLO, "", "", meta, List.copyOf(pecas)));
            });
        }
        return mapa;
    }

    private Item itemDe(HologramaSalvo salvo, boolean fixo) {
        return new Item(salvo.id(), salvo.meta().nome(), salvo.pecas().size(), salvo.criadoEm(),
                salvo.provedor(), salvo.detalhe(), salvo.objeto(), fixo, fixo && temMiniatura(salvo.id()));
    }

    /** A miniatura chega depois do objeto: a lista confere o disco na hora. */
    private Item comMiniatura(Item item) {
        return new Item(item.id(), item.nome(), item.pecas(), item.data(), item.provedor(), item.detalhe(),
                item.objeto(), item.fixo(), temMiniatura(item.id()));
    }

    private Path arquivoDe(String id) {
        return pasta.resolve(id + ".json");
    }

    private Path miniaturaDe(String id) {
        return pasta.resolve(id + ".png");
    }

    private static String curto(String objeto) {
        return objeto.length() > 40 ? objeto.substring(0, 40) + "…" : objeto;
    }
}
