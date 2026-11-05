package dev.jarvis.gatilhos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** Guardar, achar e migrar regras: o que sobrevive ao restart e o que não volta. */
class GatilhosTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final JarvisProperties props = mock(JarvisProperties.class);
    // Segunda, 2 de novembro de 2026, 09h00 em São Paulo.
    final RelogioFalso relogio = new RelogioFalso("2026-11-02T12:00:00Z");

    @BeforeEach void preparar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("t", 42));
        when(props.acoesDeSites()).thenReturn(Map.of());
    }

    Gatilhos novo() {
        return new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json"));
    }

    @Test void regraCriadaSobreviveAoRestart() throws Exception {
        var gatilhos = novo();
        var filtro = Filtro.ler(FonteDeGatilho.SITE_CAIU, json.readTree("{\"site\":\"loja\"}"), Set.of("loja"));
        var regra = gatilhos.criar(42, FonteDeGatilho.SITE_CAIU, filtro, "pergunto se posso rodar a rotina x",
                "quando a loja cair", RegraDeGatilho.ORIGEM_CONVERSA, "p1");
        gatilhos.mudarAtiva(regra.id(), 42, false);
        assertTrue(Files.exists(pasta.resolve("gatilhos.json.bak")));

        var lida = novo().porId(regra.id()).orElseThrow();
        assertEquals("loja", lida.filtro().site());
        assertEquals("pergunto se posso rodar a rotina x", lida.instrucao());
        assertEquals("quando a loja cair", lida.evidencia());
        assertFalse(lida.ativa());
        assertEquals(regra.criadaEm(), lida.criadaEm());
        assertEquals("p1", lida.pedido());
        assertTrue(novo().apagar(regra.id(), 7).isEmpty(), "outro usuário não apaga a regra");
    }

    @Test void sitesAcoesViraRegraUmaVezSo() throws Exception {
        when(props.acoesDeSites()).thenReturn(Map.of("loja", "reinicia"));
        var gatilhos = novo();
        gatilhos.migrarSitesAcoes();
        var regras = gatilhos.listar(42);
        assertEquals(1, regras.size());
        var r = regras.getFirst();
        assertEquals(FonteDeGatilho.SITE_CAIU, r.fonte());
        assertEquals("loja", r.filtro().site());
        assertEquals("Quando loja cair, pergunte se pode rodar a rotina reinicia.", r.instrucao());
        assertEquals(RegraDeGatilho.ORIGEM_SITES_ACOES, r.origem());

        var outraPartida = novo();
        outraPartida.migrarSitesAcoes();
        assertEquals(1, outraPartida.listar(42).size(), "a segunda partida não duplica");

        outraPartida.apagar(r.id(), 42);
        var terceira = novo();
        terceira.migrarSitesAcoes();
        assertTrue(terceira.listar(42).isEmpty(), "regra migrada que o dono apagou não volta");
    }

    @Test void horarioUnicoNoPassadoERecusadoERecorrenteComecaNaProxima() throws Exception {
        var gatilhos = novo();
        var passado = Filtro.ler(FonteDeGatilho.HORARIO, json.readTree("{\"quando\":\"2026-11-02T08:00\"}"), Set.of());
        assertThrows(IllegalArgumentException.class, () -> gatilhos.criar(42, FonteDeGatilho.HORARIO, passado,
                "faço x", "x", RegraDeGatilho.ORIGEM_CONVERSA, "p"));
        var diario = Filtro.ler(FonteDeGatilho.HORARIO,
                json.readTree("{\"quando\":\"2026-11-02T08:00\",\"recorrencia\":\"diario\"}"), Set.of());
        var regra = gatilhos.criar(42, FonteDeGatilho.HORARIO, diario, "faço x", "x", RegraDeGatilho.ORIGEM_CONVERSA, "p");
        assertEquals(Instant.parse("2026-11-03T11:00:00Z"), regra.proximo(), "amanhã às 8h");
    }

    @Test void esseGatilhoEOUnicoOuORecemCriado() throws Exception {
        var gatilhos = novo();
        var vazio = Filtro.ler(FonteDeGatilho.PC_LIGOU, null, Set.of());
        var primeiro = gatilhos.criar(42, FonteDeGatilho.PC_LIGOU, vazio, "abro o navegador", "x",
                RegraDeGatilho.ORIGEM_CONVERSA, "p1");
        assertEquals(primeiro.id(), gatilhos.resolver(42, "esse gatilho").regra().id());

        relogio.avancar(Duration.ofMinutes(30));
        var segundo = gatilhos.criar(42, FonteDeGatilho.TAREFA_FALHOU, vazio, "te aviso", "x",
                RegraDeGatilho.ORIGEM_CONVERSA, "p2");
        assertEquals(segundo.id(), gatilhos.resolver(42, "").regra().id(), "o que acabou de ouvir o Combinado");
        relogio.avancar(Duration.ofMinutes(11));
        var escolha = gatilhos.resolver(42, "esse");
        assertFalse(escolha.unica(), "passou o 'acabou de criar': com dois, pergunta qual");
        assertEquals(2, escolha.candidatas().size());
        assertEquals(primeiro.id(), gatilhos.resolver(42, "o do navegador").regra().id());
        assertTrue(gatilhos.resolver(7, "").candidatas().isEmpty(), "regra de outro usuário não aparece");
    }
}
