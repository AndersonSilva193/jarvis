package dev.jarvis.gatilhos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.agente.Freio;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.PcDesligou;
import dev.jarvis.pc.PcVoltou;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.tarefas.TarefasDeFundo;
import tools.jackson.databind.json.JsonMapper;

/** Evento que casa vira tarefa — com freio, teto por hora e diário. */
class MotorDeGatilhosTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final JarvisProperties props = mock(JarvisProperties.class);
    final RelogioFalso relogio = new RelogioFalso("2026-11-02T15:00:00Z");
    final TarefasDeFundo tarefas = mock(TarefasDeFundo.class);
    final Freio freio = mock(Freio.class);
    final AtomicInteger criadas = new AtomicInteger();
    Gatilhos gatilhos;
    DiarioDeAcoes diario;
    MotorDeGatilhos motor;

    @BeforeEach void preparar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("t", 42));
        when(props.acoesDeSites()).thenReturn(Map.of());
        when(props.gatilhoMaxPorHora()).thenReturn(3);
        when(freio.podeAgirSozinho("gatilho")).thenReturn(true);
        when(tarefas.iniciarPorGatilho(anyString(), anyString(), anyString(), anyLong()))
                .thenAnswer(i -> new TarefasDeFundo.Disparo("t" + criadas.incrementAndGet(), null));
        gatilhos = new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json"));
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        motor = new MotorDeGatilhos(gatilhos, tarefas, freio, diario, props, relogio, Runnable::run);
    }

    @AfterEach void fechar() { diario.fechar(); }

    RegraDeGatilho regra(FonteDeGatilho fonte, String filtro) throws Exception {
        return gatilhos.criar(42, fonte, Filtro.ler(fonte, filtro == null ? null : json.readTree(filtro),
                Set.of("loja", "blog")), "te aviso", "x", RegraDeGatilho.ORIGEM_CONVERSA, "p");
    }

    EventoDeGatilho caiu(String site) {
        return EventoDeGatilho.de(FonteDeGatilho.SITE_CAIU, relogio.instant(), "site", site, "motivo", "HTTP 502");
    }

    @Test void filtroCasandoDisparaENaoCasandoNao() throws Exception {
        var r = regra(FonteDeGatilho.SITE_CAIU, "{\"site\":\"loja\"}");
        assertTrue(motor.processar(caiu("blog")).isEmpty());
        assertTrue(motor.processar(EventoDeGatilho.de(FonteDeGatilho.SITE_VOLTOU, relogio.instant(), "site", "loja")).isEmpty(),
                "outra fonte, mesmo site");
        assertEquals(1, motor.processar(caiu("loja")).size());
        verify(tarefas).iniciarPorGatilho(eq(r.id()), eq("te aviso"), contains("site: loja"), eq(42L));
        assertEquals(relogio.instant(), gatilhos.porId(r.id()).orElseThrow().ultimoDisparo());
    }

    @Test void regraPausadaNaoDispara() throws Exception {
        var r = regra(FonteDeGatilho.SITE_CAIU, null);
        gatilhos.mudarAtiva(r.id(), 42, false);
        assertTrue(motor.processar(caiu("loja")).isEmpty());
        verifyNoInteractions(tarefas);
    }

    @Test void noMaximoTresPorHoraPorRegra() throws Exception {
        regra(FonteDeGatilho.SITE_CAIU, null);
        for (int i = 0; i < 4; i++) { motor.processar(caiu("loja")); relogio.avancar(Duration.ofMinutes(5)); }
        verify(tarefas, times(3)).iniciarPorGatilho(anyString(), anyString(), anyString(), anyLong());
        relogio.avancar(Duration.ofMinutes(50));
        assertEquals(1, motor.processar(caiu("loja")).size(), "a primeira saiu da janela de uma hora");
    }

    @Test void freioImpedeDisparoENadaEntraNoDiario() throws Exception {
        regra(FonteDeGatilho.SITE_CAIU, null);
        when(freio.podeAgirSozinho("gatilho")).thenReturn(false);
        assertTrue(motor.processar(caiu("loja")).isEmpty());
        verifyNoInteractions(tarefas);
        assertTrue(diario.deHoje().acoes().isEmpty());
    }

    @Test void diarioRegistraRegraEventoETarefa() throws Exception {
        var r = regra(FonteDeGatilho.SITE_CAIU, "{\"site\":\"loja\"}");
        motor.processar(caiu("loja"));
        var acoes = diario.deHoje().acoes();
        assertEquals(1, acoes.size());
        var a = acoes.getFirst();
        assertEquals(TipoDeAcao.GATILHO_DISPARADO, a.tipo());
        assertEquals(OrigemDaAcao.GATILHO, a.origem());
        assertEquals("Gatilho " + r.id() + " (o site loja cair): tarefa t1", a.descricao());
        assertEquals(42, a.usuario());
    }

    @Test void tarefaRecusadaNaoContaNoTetoNemNoDiario() throws Exception {
        regra(FonteDeGatilho.SITE_CAIU, null);
        when(tarefas.iniciarPorGatilho(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new TarefasDeFundo.Disparo(null, "o Jarvis está desligando"));
        for (int i = 0; i < 5; i++) assertTrue(motor.processar(caiu("loja")).isEmpty());
        assertTrue(diario.deHoje().acoes().isEmpty());
        when(tarefas.iniciarPorGatilho(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new TarefasDeFundo.Disparo("t9", null));
        assertEquals(1, motor.processar(caiu("loja")).size());
    }

    @Test void pcLigouSoDepoisDeUmaAusencia() throws Exception {
        regra(FonteDeGatilho.PC_LIGOU, null);
        motor.aoPcVoltar(new PcVoltou(relogio.instant(), null));
        verifyNoInteractions(tarefas);
        motor.aoPcVoltar(new PcVoltou(relogio.instant(), Duration.ofMinutes(40)));
        verify(tarefas).iniciarPorGatilho(anyString(), anyString(), contains("ausenciaSegundos: 2400"), eq(42L));
    }

    @Test void pcDesligouDispara() throws Exception {
        regra(FonteDeGatilho.PC_DESLIGOU, null);
        motor.aoPcDesligar(new PcDesligou(relogio.instant().minusSeconds(95), relogio.instant()));
        verify(tarefas).iniciarPorGatilho(anyString(), anyString(), contains("evento: pc_desligou"), eq(42L));
    }
}
