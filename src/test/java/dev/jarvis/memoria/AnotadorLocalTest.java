package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.BancadaLocal;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/** O validador do modelo do PC: resposta fora do JSON combinado manda o trecho ao Claude. */
class AnotadorLocalTest {

    private static final long DONO = 42L;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-22T14:00:00Z"), ZoneId.of("UTC"));

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final AtomicInteger chamadasAoClaude = new AtomicInteger();

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Memoria("", "", true, 5));
    }

    private static String resposta(String evidencia) {
        return "{\"candidatos\":[{\"categoria\":\"perfil\",\"assunto\":\"editor\","
                + "\"conteudo\":\"Usa o Neovim para programar.\",\"evidencia\":\"" + evidencia
                + "\",\"acao\":\"novo\"}]}";
    }

    private void analisar(ProvedorDeModelo modelo, MemoriaService memoria) throws Exception {
        var anotador = new AnotadorDeMemoria(memoria, modelo, json, props(), relogio, null);
        anotador.registrarTurno(DONO, DONO, "telegram", "hoje em dia eu programo no neovim");
        for (int i = 1; i < 10; i++) anotador.registrarTurno(DONO, DONO, "telegram", "enchimento " + i);
        assertTrue(anotador.aguardar(Duration.ofSeconds(10)), "a análise terminou");
    }

    private MemoriaService memoria() {
        return new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json,
                relogio, ZoneId.of("America/Sao_Paulo"));
    }

    @Test
    @DisplayName("resposta ilegível no local: o Claude refaz e é a dele que grava")
    void respostaIlegivelVaiAoClaude() throws Exception {
        bancada = new BancadaLocal(pasta).responde("Claro! Anotei que ele usa o Neovim.");
        ProvedorDeModelo claude = pedido -> {
            chamadasAoClaude.incrementAndGet();
            return new RespostaDoModelo(List.of(new Bloco.Texto(resposta("eu programo no neovim"))), null, null);
        };
        var memoria = memoria();

        analisar(bancada.apoio(claude), memoria);

        assertEquals(1, chamadasAoClaude.get());
        assertEquals(1, memoria.assuntos(DONO).size());
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get(ProvedorDeApoio.REPROVADO));
    }

    @Test
    @DisplayName("sem prova de citação: evidência reescrita no local grava sem o Claude")
    void evidenciaReescritaFicaNoLocal() throws Exception {
        bancada = new BancadaLocal(pasta).responde(resposta("ele adora o neovim"));
        ProvedorDeModelo claude = pedido -> {
            chamadasAoClaude.incrementAndGet();
            return new RespostaDoModelo(List.of(new Bloco.Texto("{\"candidatos\":[]}")), null, null);
        };
        var memoria = memoria();

        analisar(bancada.apoio(claude), memoria);

        assertEquals(0, chamadasAoClaude.get());
        assertEquals(1, memoria.assuntos(DONO).size());
    }

    @Test
    @DisplayName("evidência literal no local: grava sem o Claude")
    void evidenciaLiteralFicaNoLocal() throws Exception {
        bancada = new BancadaLocal(pasta).responde(resposta("eu programo no neovim"));
        ProvedorDeModelo claude = pedido -> {
            chamadasAoClaude.incrementAndGet();
            return new RespostaDoModelo(List.of(new Bloco.Texto("{\"candidatos\":[]}")), null, null);
        };
        var memoria = memoria();

        analisar(bancada.apoio(claude), memoria);

        assertEquals(0, chamadasAoClaude.get());
        assertEquals(1, memoria.assuntos(DONO).size());
    }
}
