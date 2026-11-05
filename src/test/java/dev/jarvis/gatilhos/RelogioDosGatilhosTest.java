package dev.jarvis.gatilhos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** O horário com relógio fixo: dispara na hora, avança pela repetição, e o atrasado demais pula. */
class RelogioDosGatilhosTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final JarvisProperties props = mock(JarvisProperties.class);
    // Segunda, 2 de novembro de 2026, 07h59 em São Paulo (UTC-3).
    final RelogioFalso relogio = new RelogioFalso("2026-11-02T10:59:00Z");
    final MotorDeGatilhos motor = mock(MotorDeGatilhos.class);
    Gatilhos gatilhos;
    RelogioDosGatilhos horario;

    @BeforeEach void preparar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("t", 42));
        when(props.acoesDeSites()).thenReturn(Map.of());
        gatilhos = new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json"));
        horario = new RelogioDosGatilhos(gatilhos, motor, relogio);
    }

    RegraDeGatilho regra(String filtro) throws Exception {
        return gatilhos.criar(42, FonteDeGatilho.HORARIO,
                Filtro.ler(FonteDeGatilho.HORARIO, json.readTree(filtro), Set.of()),
                "te mando o resumo", "toda segunda às 8h", RegraDeGatilho.ORIGEM_CONVERSA, "p");
    }

    @Test void semanalDisparaNaHoraEAvancaUmaSemana() throws Exception {
        var r = regra("{\"quando\":\"2026-11-02T08:00\",\"recorrencia\":\"semanal\",\"dias_da_semana\":[\"segunda\"]}");
        assertEquals(Instant.parse("2026-11-02T11:00:00Z"), r.proximo());
        horario.conferir();
        verifyNoInteractions(motor);

        relogio.avancar(Duration.ofSeconds(70));
        horario.conferir();
        var evento = ArgumentCaptor.forClass(EventoDeGatilho.class);
        verify(motor).processar(evento.capture());
        assertEquals(FonteDeGatilho.HORARIO, evento.getValue().fonte());
        assertEquals(r.id(), evento.getValue().dado("regra"));
        assertEquals("2026-11-02T11:00:00Z", evento.getValue().dado("previsto"));
        var depois = gatilhos.porId(r.id()).orElseThrow();
        assertEquals(Instant.parse("2026-11-09T11:00:00Z"), depois.proximo());
        assertTrue(depois.ativa());

        horario.conferir();
        verify(motor, times(1)).processar(any());
    }

    @Test void unicoDesligaDepoisDeDisparar() throws Exception {
        var r = regra("{\"quando\":\"2026-11-02T08:00\"}");
        relogio.avancar(Duration.ofMinutes(2));
        horario.conferir();
        verify(motor).processar(any());
        var depois = gatilhos.porId(r.id()).orElseThrow();
        assertFalse(depois.ativa());
        assertNull(depois.proximo());
    }

    @Test void atrasoDeVinteMinutosPulaParaAProxima() throws Exception {
        var r = regra("{\"quando\":\"2026-11-02T08:00\",\"recorrencia\":\"diario\"}");
        relogio.avancar(Duration.ofMinutes(21));
        horario.conferir();
        verifyNoInteractions(motor);
        assertEquals(Instant.parse("2026-11-03T11:00:00Z"), gatilhos.porId(r.id()).orElseThrow().proximo());
    }
}
