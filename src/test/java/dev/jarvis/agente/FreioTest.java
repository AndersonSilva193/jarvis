package dev.jarvis.agente;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import tools.jackson.databind.json.JsonMapper;

class FreioTest {
    @TempDir Path pasta;
    final JarvisProperties props = mock(JarvisProperties.class);
    final MedidorDeCusto medidor = mock(MedidorDeCusto.class);
    final AvisosProativos avisos = mock(AvisosProativos.class);
    final RelogioFalso relogio = new RelogioFalso("2026-11-03T12:00:00Z");
    Freio criar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        return new Freio(props, medidor, avisos, new FalasService(), JsonMapper.builder().build(), relogio, pasta.resolve("freio.json"));
    }

    @Test void pausaSobreviveAoRestartERetomadaTambem() {
        var freio = criar();
        assertTrue(freio.podeAgirSozinho("anotador"));
        freio.pausar("telegram");
        var outro = criar();
        assertFalse(outro.podeAgirSozinho("tarefa"));
        assertEquals("telegram", outro.retrato().canal());
        assertEquals(relogio.instant(), outro.retrato().desde());
        outro.retomar("hud");
        assertTrue(criar().podeAgirSozinho("gatilho"));
        assertEquals("hud", criar().retrato().canal());
    }

    @Test void todosOsOuvintesRodamMesmoComFalha() {
        var freio = criar(); var chamadas = new AtomicInteger();
        freio.aoPausar(() -> { throw new IllegalStateException("falha"); });
        freio.aoPausar(chamadas::incrementAndGet);
        freio.pausar("hud");
        assertEquals(1, chamadas.get());
    }

    @Test void analiseAntigaNaoGravaDepoisDePausarERetomar() throws Exception {
        var freio = criar(); long inicio = freio.geracao();
        var chamadas = new AtomicInteger();
        freio.pausar("hud"); freio.retomar("telegram");
        assertNull(freio.escrever(inicio, "anotador", chamadas::incrementAndGet));
        assertEquals(0, chamadas.get());
        assertEquals(1, freio.escrever(freio.geracao(), "anotador", chamadas::incrementAndGet));
    }

    @Test void tetoBloqueiaEAvisaUmaVezMesmoComRestartENaoEContornadoPorRetomar() {
        when(props.tetoAutonomoDeCusto()).thenReturn(1.0);
        var freio = criar(); var chamadas = new AtomicInteger();
        freio.aoPausar(chamadas::incrementAndGet);
        when(medidor.gastoAutonomoDeHoje()).thenReturn(0.99);
        assertTrue(freio.podeAgirSozinho("tarefa"));
        when(medidor.gastoAutonomoDeHoje()).thenReturn(1.0);
        assertFalse(freio.podeAgirSozinho("anotador"));
        freio.avaliarCusto(); freio.retomar("hud");
        assertFalse(freio.podeAgirSozinho("gatilho"));
        assertFalse(criar().podeAgirSozinho("tarefa"));
        assertEquals(1, chamadas.get());
        verify(avisos, times(1)).avisar(eq("freio:custo"), eq("2026-11-03"), anyString(), isNull());
        relogio.avancar(Duration.ofDays(1));
        when(medidor.gastoAutonomoDeHoje()).thenReturn(0.0);
        assertTrue(freio.podeAgirSozinho("tarefa"));
    }

    @Test void tetoNaoLiberaPausaManualNaViradaDoDia() {
        var freio = criar(); freio.pausar("telegram"); relogio.avancar(Duration.ofDays(1));
        assertFalse(freio.podeAgirSozinho("anotador"));
    }

    @Test void arquivosIrrecuperaveisFalhamPausados() throws Exception {
        Files.writeString(pasta.resolve("freio.json"), "quebrado");
        Files.writeString(pasta.resolve("freio.json.bak"), "também");
        assertEquals(Freio.Estado.PAUSADO, criar().retrato().estado());
    }

    @ParameterizedTest @ValueSource(strings = {"Jarvis, para tudo", "pausa tudo", "PARA GERAL!", "Jarvis, pare tudo.",
            "Jarvis, para tudo já", "para tudo por favor", "por favor pausa tudo"})
    void reconhecePausa(String texto) { assertEquals(ComandoDoFreio.Acao.PAUSAR, ComandoDoFreio.reconhecer(texto).orElseThrow()); }
    @ParameterizedTest @ValueSource(strings = {"pode voltar", "retoma", "volta ao normal", "Jarvis volta ao normal"})
    void reconheceRetomada(String texto) { assertEquals(ComandoDoFreio.Acao.RETOMAR, ComandoDoFreio.reconhecer(texto).orElseThrow()); }
    @ParameterizedTest @ValueSource(strings = {"para", "não pausa tudo", "pausa tudo sobre esse arquivo", "pode voltar o arquivo", "me explica para tudo"})
    void naoConfundePedidos(String texto) { assertTrue(ComandoDoFreio.reconhecer(texto).isEmpty()); }
}
