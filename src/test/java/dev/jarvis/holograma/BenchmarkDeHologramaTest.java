package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.Cancelamento;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/** O benchmark: cada gerador sem reserva, fora da galeria, um relatório gravado e os piores na frente. */
class BenchmarkDeHologramaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** Texto antes do JSON, dez peças numa linha só (rep) e o fim. */
    private static final String RESPOSTA = "Claro! Aqui está:\n"
            + "{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"um cubo\"}\n"
            + "{\"t\":\"p\",\"id\":\"cubo\",\"n\":\"Cubo\",\"g\":\"corpo\",\"f\":\"box\",\"p\":[0,0,0],\"s\":[1,1,1],"
            + "\"c\":\"#38bdf8\",\"e\":[0,1,0],\"rep\":{\"n\":10,\"d\":[0.5,0,0]}}\n"
            + "{\"t\":\"fim\"}";

    @TempDir
    Path pasta;
    private HologramaService servico;
    private BenchmarkDeHolograma benchmark;

    @AfterEach
    void fechar() {
        if (benchmark != null) benchmark.fechar();
        if (servico != null) servico.fechar();
    }

    @Test
    void medeCadaObjetoComCadaGeradorSemReservaNemGaleria() throws Exception {
        Fixo anthropic = new Fixo(RESPOSTA);
        Fixo local = new Fixo(RESPOSTA);
        GaleriaDeHologramas galeria = new GaleriaDeHologramas(JSON, pasta.resolve("galeria"),
                new ExemplosDeHolograma(), new HologramaLineParser(JSON));
        int itensAntes = galeria.listar().size();
        servico = new HologramaService(anthropic, local, () -> new DisponibilidadeLocal.Situacao(false, "pc_offline"),
                () -> "", props(), new PromptDeHolograma(), new HologramaLineParser(JSON), Duration.ofSeconds(10),
                Duration.ofSeconds(10), HologramaService.PRIMEIRA_RESPOSTA, galeria);
        benchmark = new BenchmarkDeHolograma(servico, JSON, pasta.resolve("benchmark"),
                Clock.fixed(Instant.parse("2026-10-30T12:00:00Z"), ZoneId.of("America/Sao_Paulo")));

        assertTrue(benchmark.iniciar(DetalheHolograma.RAPIDO, List.of("local", "anthropic")).isPresent());
        assertTrue(benchmark.iniciar(DetalheHolograma.RAPIDO, List.of("anthropic")).isEmpty(), "um por vez");
        BenchmarkDeHolograma.Andamento fim = esperar();

        BenchmarkDeHolograma.Relatorio relatorio = fim.relatorio();
        assertNotNull(relatorio);
        assertEquals(BenchmarkDeHolograma.OBJETOS.size() * 2, relatorio.resultados().size());
        // O local fora de alcance não é trocado pelo Haiku: fica indisponível e nem é chamado.
        assertEquals(0, local.chamadas.get());
        assertEquals(BenchmarkDeHolograma.OBJETOS.size(), anthropic.chamadas.get());
        for (MedidaDaGeracao m : relatorio.resultados()) {
            if (m.provedorPedido().equals("local")) {
                assertEquals("pc_offline", m.indisponivel());
                assertEquals(0, m.pecas());
            } else {
                assertTrue(m.completo(), m.toString());
                assertEquals(10, m.pecas());
                assertEquals(1, m.linhas());
                assertEquals(1, m.descartadas(), "o texto antes do JSON é uma linha descartada");
                assertNotNull(m.primeiraPecaMs());
                assertTrue(m.ndjson().contains("\"rep\""), "o relatório guarda a linha como o modelo a escreveu");
            }
        }
        assertEquals(itensAntes, galeria.listar().size(), "medir não guarda nada na galeria");
        // Indisponível é ambiente: não entra nos piores. O Haiku perdeu uma linha em cada objeto.
        assertTrue(relatorio.piores().stream().allMatch(p -> p.provedor().equals("anthropic")));
        assertTrue(relatorio.piores().getFirst().porque().contains("1 de 2 linhas descartadas"));
        assertTrue(Files.exists(pasta.resolve("benchmark/2026-10-30-090000.json")));
        String md = Files.readString(pasta.resolve("benchmark/2026-10-30-090000.md"));
        assertTrue(md.contains("| motor V8 | local | — |"), md);
        assertTrue(md.contains("indisponível (pc_offline)"), md);
        assertTrue(md.contains("Claro! Aqui está:"), "o relatório mostra a linha descartada");
        assertFalse(fim.rodando());
    }

    @Test
    void osPioresVemDoMaisGraveAoMenos() {
        List<BenchmarkDeHolograma.Pior> piores = BenchmarkDeHolograma.piores(List.of(
                medida("pobre", 10, 0, true, ""),
                medida("vazio", 0, 3, false, ""),
                medida("sujo", 30, 10, true, ""),
                medida("cortado", 20, 0, false, ""),
                medida("bom", 30, 0, true, ""),
                medida("fora", 0, 0, false, "pc_offline")));
        assertEquals(List.of("vazio", "cortado", "sujo", "pobre"),
                piores.stream().map(BenchmarkDeHolograma.Pior::objeto).toList());
    }

    private static MedidaDaGeracao medida(String objeto, int linhas, int descartadas, boolean completo,
            String indisponivel) {
        return new MedidaDaGeracao(objeto, "normal", "local", "local", "qwen", linhas == 0 ? null : 1_000L, 5_000,
                linhas, linhas, 1, descartadas, 0, 0, null, null, null,
                completo ? SaidaHolograma.COMPLETO : SaidaHolograma.INCOMPLETO, completo ? "" : "o modelo bateu no teto de tokens",
                indisponivel, "modelo", List.of(), "");
    }

    private BenchmarkDeHolograma.Andamento esperar() throws InterruptedException {
        long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (benchmark.andamento().rodando()) {
            assertTrue(System.nanoTime() < limite, "o benchmark não terminou");
            Thread.sleep(20);
        }
        return benchmark.andamento();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512), null, null, null,
                null, null, null, null,
                new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1, new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Holograma("anthropic", "modelo-holograma", 0.4));
    }

    /** Responde sempre o mesmo texto, de uma vez. */
    private static final class Fixo implements ProvedorDeModelo {
        private final String texto;
        final AtomicInteger chamadas = new AtomicInteger();

        Fixo(String texto) {
            this.texto = texto;
        }

        @Override
        public RespostaDoModelo responder(PedidoAoModelo pedido) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto, Cancelamento cancelamento)
                throws IOException {
            chamadas.incrementAndGet();
            aoTexto.accept(texto);
            return new RespostaDoModelo(List.of(new Bloco.Texto(texto)), RespostaDoModelo.MotivoDeParada.FIM,
                    new RespostaDoModelo.Uso(1_000, 500, 0, 0, 0));
        }
    }
}
