package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.OllamaDireto;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import tools.jackson.databind.json.JsonMapper;

/**
 * O benchmark do gerador local sem a VPS: o mesmo {@link BenchmarkDeHolograma} e o mesmo
 * {@link HologramaService}, com o Ollama desta máquina no lugar do agente do PC. Desligado por
 * padrão — leva de 5 a 20 minutos e precisa do modelo baixado:
 *
 * <pre>
 * mvn -B test -Dtest=BenchmarkComOllamaTest -Dbenchmark.ollama=http://127.0.0.1:11434 \
 *     [-Dbenchmark.modelo=qwen3.5:9b] [-Dbenchmark.detalhe=normal]
 * </pre>
 *
 * O relatório sai em {@code target/benchmark-holograma/}. O Haiku não entra: sem a chave da VPS,
 * ele é medido pelo {@code scripts/benchmark-holograma.mjs} contra a API de verdade.
 */
@EnabledIfSystemProperty(named = "benchmark.ollama", matches = "https?://.+")
class BenchmarkComOllamaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void geraOsDezObjetosNoOllamaDestaMaquina() throws Exception {
        String modelo = System.getProperty("benchmark.modelo", "qwen3.5:9b");
        DetalheHolograma detalhe = DetalheHolograma.porChave(System.getProperty("benchmark.detalhe")).orElseThrow();
        // Os números do agente: ollama.contexto=8192 e a temperatura do HOLOGRAMA_TEMPERATURA.
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("sem-chave", "modelo-a", 1024, 512), null, null, null,
                null, null, null, null,
                new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1, new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Holograma("local", "claude-haiku-5-5", 0.4));
        ProvedorDeModelo semAnthropic = pedido -> {
            throw new IOException("sem a chave da Anthropic neste teste");
        };
        HologramaService servico = new HologramaService(semAnthropic,
                new OllamaDireto(JSON, System.getProperty("benchmark.ollama"), modelo, 8192),
                () -> new DisponibilidadeLocal.Situacao(true, ""), () -> modelo, props, new PromptDeHolograma(),
                new HologramaLineParser(JSON), HologramaService.PRAZO, Duration.ofSeconds(3),
                HologramaService.PRIMEIRA_RESPOSTA);
        BenchmarkDeHolograma benchmark = new BenchmarkDeHolograma(servico, JSON,
                Path.of("target", "benchmark-holograma"), Clock.systemDefaultZone());
        try {
            benchmark.iniciar(detalhe, List.of(HologramaService.LOCAL)).orElseThrow();
            while (benchmark.andamento().rodando()) {
                Thread.sleep(2_000);
            }
            BenchmarkDeHolograma.Andamento fim = benchmark.andamento();
            assertNotNull(fim.relatorio(), "o benchmark parou sem relatório");
            System.out.println(fim.markdown());
        } finally {
            benchmark.fechar();
            servico.fechar();
        }
    }
}
