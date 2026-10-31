package dev.jarvis.holograma;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.ArquivoAtomico;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Gera os dez objetos de referência com cada gerador pedido, um de cada vez, e guarda um relatório
 * (JSON e Markdown) em {@code HOLOGRAMA_PASTA/benchmark/}. É como se compara o Qwen do PC com o
 * Haiku, e como se mede o efeito de uma mudança no {@code holograma-prompt.txt}.
 *
 * <p>Cada geração passa pelo mesmo caminho da HUD — prompt, provedor, stream, montagem, continuação —
 * pelo {@link HologramaService#medir}, que tira só três coisas: a reserva (o local que caiu não pode
 * ser medido como Haiku), a galeria e a retomada (medir não troca o que o dono guardou). Os números
 * são os da linha de log de fim de geração.
 *
 * <p>Um benchmark por vez, em sequência: há uma GPU só no PC, e duas gerações em paralelo mediriam a
 * fila, não o modelo. Gerar pela HUD no meio corta a medição em andamento, que entra no relatório
 * como {@code substituido}.
 */
@Component
public class BenchmarkDeHolograma {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkDeHolograma.class);

    /** Os objetos de referência: mecânica, biologia, química, arquitetura, astronomia, eletrônica. */
    public static final List<String> OBJETOS = List.of("motor V8", "DNA", "átomo de carbono", "torre Eiffel",
            "coração humano", "sistema solar", "drone quadricóptero", "guitarra", "prédio de 10 andares",
            "teclado mecânico");
    public static final List<String> PROVEDORES = List.of(HologramaService.LOCAL, HologramaService.ANTHROPIC);
    /** Sobre o pior caso de uma geração (a inicial e as continuações): quem corta é o próprio gerador. */
    private static final Duration ESPERA = HologramaService.PRAZO_TOTAL.plusSeconds(30);
    private static final String PASTA = "benchmark";
    private static final DateTimeFormatter NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    /** Quantos piores o relatório destaca: é a lista que orienta o ajuste do prompt. */
    static final int PIORES = 5;

    private final HologramaService holograma;
    private final JsonMapper json;
    private final Path pasta;
    private final Clock relogio;
    private final AtomicBoolean rodando = new AtomicBoolean();
    private final ExecutorService execucao = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("holograma-benchmark").factory());
    private volatile Andamento andamento = new Andamento(false, 0, 0, "", null, null);

    @Autowired
    public BenchmarkDeHolograma(HologramaService holograma, JsonMapper json, JarvisProperties props) {
        this(holograma, json, props.pastaDoHolograma().resolve(PASTA), Clock.system(props.zona()));
    }

    public BenchmarkDeHolograma(HologramaService holograma, JsonMapper json, Path pasta, Clock relogio) {
        this.holograma = holograma;
        this.json = json;
        this.pasta = pasta;
        this.relogio = relogio;
    }

    /**
     * Onde está o benchmark: rodando ou não, quantas gerações de quantas, a de agora e o último
     * relatório pronto.
     *
     * @param relatorio  o último relatório inteiro; {@code null} enquanto nenhum terminou
     * @param markdown   o mesmo relatório em Markdown, como foi gravado
     */
    public record Andamento(boolean rodando, int feitas, int total, String atual, Relatorio relatorio,
            String markdown) { }

    /** Um provedor no relatório: as medianas e as somas que comparam um gerador com o outro. */
    public record ResumoDoProvedor(int geracoes, int completas, Long medianaPrimeiraPecaMs, long medianaTotalMs,
            int pecas, int descartadas, double custoUsd) { }

    /** Um dos piores resultados, com o porquê escrito em Java. */
    public record Pior(String objeto, String provedor, String porque) { }

    public record Relatorio(String inicio, String fim, String detalhe, List<String> provedores,
            List<MedidaDaGeracao> resultados, Map<String, ResumoDoProvedor> resumo, List<Pior> piores,
            String arquivo) { }

    public Andamento andamento() {
        return andamento;
    }

    /**
     * Começa e volta na hora. Vazio quando já há um rodando.
     *
     * @param provedores {@code local} e/ou {@code anthropic}, na ordem em que cada objeto é medido
     */
    public Optional<Andamento> iniciar(DetalheHolograma detalhe, List<String> provedores) {
        if (!rodando.compareAndSet(false, true)) {
            return Optional.empty();
        }
        int total = OBJETOS.size() * provedores.size();
        Andamento anterior = andamento;
        andamento = new Andamento(true, 0, total, "", anterior.relatorio(), anterior.markdown());
        execucao.submit(() -> rodar(detalhe, List.copyOf(provedores)));
        return Optional.of(andamento);
    }

    private void rodar(DetalheHolograma detalhe, List<String> provedores) {
        try {
            String inicio = ZonedDateTime.now(relogio).toOffsetDateTime().toString();
            List<MedidaDaGeracao> resultados = new ArrayList<>();
            int total = OBJETOS.size() * provedores.size();
            for (String objeto : OBJETOS) {
                for (String provedor : provedores) {
                    andamento = new Andamento(true, resultados.size(), total, objeto + " · " + provedor,
                            andamento.relatorio(), andamento.markdown());
                    MedidaDaGeracao medida = medir(objeto, detalhe, provedor);
                    if (medida == null) return; // interrompido: o servidor está desligando
                    resultados.add(medida);
                    log.info("Benchmark do holograma {}/{}: '{}' {} → {} peças, {} descartadas, 1ª peça {} ms, "
                            + "total {} ms, {}", resultados.size(), total, objeto, provedor, medida.pecas(),
                            medida.descartadas(), medida.primeiraPecaMs(), medida.totalMs(), medida.status());
                }
            }
            String fim = ZonedDateTime.now(relogio).toOffsetDateTime().toString();
            String nome = NOME.format(ZonedDateTime.now(relogio));
            Relatorio relatorio = new Relatorio(inicio, fim, detalhe.chave(), provedores, List.copyOf(resultados),
                    resumir(resultados, provedores), piores(resultados), pasta.resolve(nome + ".md").toString());
            String markdown = markdown(relatorio);
            gravar(nome, relatorio, markdown);
            andamento = new Andamento(false, resultados.size(), total, "", relatorio, markdown);
        } catch (RuntimeException e) {
            log.warn("Benchmark do holograma falhou: {}", e.toString());
            andamento = new Andamento(false, andamento.feitas(), andamento.total(), "", andamento.relatorio(),
                    andamento.markdown());
        } finally {
            rodando.set(false);
            if (andamento.rodando()) {
                andamento = new Andamento(false, andamento.feitas(), andamento.total(), "", andamento.relatorio(),
                        andamento.markdown());
            }
        }
    }

    /** Uma geração, esperada até o pior caso dela. {@code null} quando a thread foi interrompida. */
    private MedidaDaGeracao medir(String objeto, DetalheHolograma detalhe, String provedor) {
        CompletableFuture<MedidaDaGeracao> pronta = new CompletableFuture<>();
        holograma.medir(objeto, detalhe, provedor, pronta::complete);
        try {
            return pronta.get(ESPERA.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (TimeoutException | ExecutionException e) {
            // Não deveria: o gerador tem prazo próprio e sempre mede. Entra no relatório como falha.
            return new MedidaDaGeracao(objeto, detalhe.chave(), provedor, "", "", null, ESPERA.toMillis(), 0, 0, 0,
                    0, 0, 0, null, null, null, SaidaHolograma.INCOMPLETO, "o gerador não respondeu", "",
                    "sem_medida", List.of(), "");
        }
    }

    private void gravar(String nome, Relatorio relatorio, String markdown) {
        try {
            ArquivoAtomico.gravar(pasta.resolve(nome + ".json"), json.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(relatorio));
            ArquivoAtomico.gravar(pasta.resolve(nome + ".md"), markdown);
            log.info("Benchmark do holograma gravado em {}", pasta.resolve(nome + ".md"));
        } catch (IOException e) {
            log.warn("Benchmark do holograma não foi gravado: {}", e.toString());
        }
    }

    static Map<String, ResumoDoProvedor> resumir(List<MedidaDaGeracao> resultados, List<String> provedores) {
        Map<String, ResumoDoProvedor> resumo = new LinkedHashMap<>();
        for (String provedor : provedores) {
            List<MedidaDaGeracao> doProvedor = resultados.stream()
                    .filter(m -> m.provedorPedido().equals(provedor)).toList();
            List<Long> primeiras = doProvedor.stream().map(MedidaDaGeracao::primeiraPecaMs)
                    .filter(m -> m != null).toList();
            resumo.put(provedor, new ResumoDoProvedor(doProvedor.size(),
                    (int) doProvedor.stream().filter(MedidaDaGeracao::completo).count(),
                    primeiras.isEmpty() ? null : mediana(primeiras),
                    mediana(doProvedor.stream().map(MedidaDaGeracao::totalMs).toList()),
                    doProvedor.stream().mapToInt(MedidaDaGeracao::pecas).sum(),
                    doProvedor.stream().mapToInt(MedidaDaGeracao::descartadas).sum(),
                    Math.round(doProvedor.stream().mapToDouble(m -> m.custoUsd() == null ? 0 : m.custoUsd()).sum()
                            * 1_000_000.0) / 1_000_000.0));
        }
        return resumo;
    }

    private static long mediana(List<Long> valores) {
        if (valores.isEmpty()) return 0;
        List<Long> ordenados = valores.stream().sorted().toList();
        return ordenados.get(ordenados.size() / 2);
    }

    /**
     * Os piores, do mais grave ao menos: não completou (ou nem desenhou), depois a fração de linhas
     * descartadas, depois o objeto pobre — menos linhas de peça do que o nível pede. O local que nem
     * foi tentado fica de fora: é ambiente, não resultado do prompt.
     */
    static List<Pior> piores(List<MedidaDaGeracao> resultados) {
        record Nota(MedidaDaGeracao medida, double gravidade, String porque) { }
        List<Nota> notas = new ArrayList<>();
        for (MedidaDaGeracao m : resultados) {
            if (!m.indisponivel().isEmpty()) continue;
            int escritas = m.linhas() + m.descartadas();
            double fracao = escritas == 0 ? 0 : (double) m.descartadas() / escritas;
            int minimo = linhasMinimas(m.detalhe());
            if (m.pecas() == 0) {
                notas.add(new Nota(m, 4, "nenhuma peça (" + m.motivo() + ")"));
            } else if (!m.completo()) {
                notas.add(new Nota(m, 3 + fracao, "incompleto: " + m.motivo() + ", " + m.pecas() + " peças"));
            } else if (m.descartadas() > 0) {
                notas.add(new Nota(m, 2 + fracao, m.descartadas() + " de " + escritas + " linhas descartadas"));
            } else if (m.linhas() < minimo) {
                notas.add(new Nota(m, 1 + (double) (minimo - m.linhas()) / minimo,
                        m.linhas() + " linhas de peça, abaixo das " + minimo + " do nível"));
            }
        }
        return notas.stream().sorted(Comparator.comparingDouble(Nota::gravidade).reversed()).limit(PIORES)
                .map(n -> new Pior(n.medida().objeto(), n.medida().provedorPedido(), n.porque())).toList();
    }

    /** O piso do nível no prompt (15, 30, 60 linhas), sem as linhas de meta e de fim. */
    static int linhasMinimas(String detalhe) {
        return switch (detalhe) {
            case "rapido" -> 13;
            case "detalhado" -> 58;
            default -> 28;
        };
    }

    static String markdown(Relatorio r) {
        StringBuilder md = new StringBuilder();
        md.append("# Benchmark do holograma\n\n")
                .append("Nível `").append(r.detalhe()).append("` · ").append(r.inicio()).append(" → ")
                .append(r.fim()).append("\n\n## Por gerador\n\n")
                .append("| Gerador | Completas | 1ª peça (mediana) | Total (mediana) | Peças | Descartadas | Custo |\n")
                .append("|---|---|---|---|---|---|---|\n");
        r.resumo().forEach((provedor, s) -> md.append("| ").append(provedor).append(" | ").append(s.completas())
                .append("/").append(s.geracoes()).append(" | ")
                .append(s.medianaPrimeiraPecaMs() == null ? "—" : segundos(s.medianaPrimeiraPecaMs())).append(" | ")
                .append(segundos(s.medianaTotalMs())).append(" | ").append(s.pecas()).append(" | ")
                .append(s.descartadas()).append(" | ").append(dolares(s.custoUsd())).append(" |\n"));
        md.append("\n## Cada geração\n\n")
                .append("| Objeto | Gerador | 1ª peça | Total | Peças | Linhas | Com rep/rad | Descartadas | Chamadas | Status |\n")
                .append("|---|---|---|---|---|---|---|---|---|---|\n");
        for (MedidaDaGeracao m : r.resultados()) {
            md.append("| ").append(m.objeto()).append(" | ").append(m.provedorPedido())
                    .append(m.modelo().isEmpty() ? "" : " (" + m.modelo() + ")").append(" | ")
                    .append(m.primeiraPecaMs() == null ? "—" : segundos(m.primeiraPecaMs())).append(" | ")
                    .append(segundos(m.totalMs())).append(" | ").append(m.pecas()).append(" | ")
                    .append(m.linhas()).append(" | ").append(comRepeticao(m)).append(" | ")
                    .append(m.descartadas()).append(" | ").append(m.chamadas())
                    .append(" | ").append(status(m)).append(" |\n");
        }
        md.append("\n## Piores\n\n");
        if (r.piores().isEmpty()) {
            md.append("Nenhum: tudo completo, sem linha descartada e no tamanho do nível.\n");
        }
        for (Pior p : r.piores()) {
            md.append("- **").append(p.objeto()).append("** (").append(p.provedor()).append("): ")
                    .append(p.porque()).append('\n');
        }
        List<MedidaDaGeracao> comDescarte = r.resultados().stream().filter(m -> !m.exemplosDescartados().isEmpty())
                .toList();
        if (!comDescarte.isEmpty()) {
            md.append("\n## Linhas descartadas (até 5 por geração)\n\n");
            for (MedidaDaGeracao m : comDescarte) {
                md.append("**").append(m.objeto()).append("** (").append(m.provedorPedido()).append(")\n\n");
                for (String linha : m.exemplosDescartados()) {
                    md.append("    ").append(linha.replace('\n', ' ')).append('\n');
                }
                md.append('\n');
            }
        }
        return md.toString();
    }

    /**
     * Quantas linhas de peça usaram {@code rep} ou {@code rad}: diz se o modelo está escrevendo
     * cópias à mão, que é lento e o que mais estoura o prazo do local.
     */
    static long comRepeticao(MedidaDaGeracao m) {
        return m.ndjson().lines().filter(l -> l.contains("\"rep\"") || l.contains("\"rad\"")).count();
    }

    private static String status(MedidaDaGeracao m) {
        if (!m.indisponivel().isEmpty()) return "indisponível (" + m.indisponivel() + ")";
        return m.completo() ? "completo" : "incompleto: " + m.motivo();
    }

    private static String segundos(long ms) {
        return String.format(Locale.ROOT, "%.1f s", ms / 1000.0);
    }

    private static String dolares(double valor) {
        return valor == 0 ? "—" : String.format(Locale.ROOT, "US$ %.4f", valor);
    }

    @PreDestroy
    public void fechar() {
        execucao.shutdownNow();
    }
}
