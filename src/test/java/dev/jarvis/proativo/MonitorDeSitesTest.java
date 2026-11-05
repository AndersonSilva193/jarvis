package dev.jarvis.proativo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.gatilhos.EventoDeGatilho;
import dev.jarvis.gatilhos.FonteDeGatilho;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** O monitor que avisa sozinho: quando fala, quando cala e quando pergunta. */
class MonitorDeSitesTest {

    private static final long DONO = 777L;

    @TempDir
    Path pasta;

    private final List<HistoricoDeDisponibilidade> criados = new ArrayList<>();

    /** Sem fechar o executor de gravação o @TempDir não consegue ser apagado no Windows. */
    @AfterEach
    void fechar() {
        criados.forEach(HistoricoDeDisponibilidade::fechar);
    }

    private static JarvisProperties props(long usuario, String silencio, String acoes) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo",
                "loja=https://loja.example", new JarvisProperties.Telegram("t", usuario),
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, new JarvisProperties.Proativo(silencio, null, 5, acoes));
    }

    /** O cenário montado por inteiro, para cada teste dizer só o que muda. */
    private final class Cenario {
        final RelogioFalso relogio;
        final JarvisProperties props;
        final TelegramFalso telegram;
        final SitesFalso sites;
        final AvisosProativos avisos;
        final Aprovacoes aprovacoes;
        final OperacoesPcService e2;
        final MonitorDeSites monitor;

        Cenario(String instante, String silencio, String acoes) {
            relogio = new RelogioFalso(instante);
            props = props(DONO, silencio, acoes);
            telegram = new TelegramFalso(props);
            sites = new SitesFalso(props);
            avisos = new AvisosProativos(telegram, props, relogio);
            aprovacoes = new Aprovacoes(relogio);
            e2 = new OperacoesPcService(JsonMapper.builder().build(),
                    pasta.resolve("operacoes.json"), relogio);
            var historico = new HistoricoDeDisponibilidade(props, JsonMapper.builder().build(),
                    relogio, pasta.resolve("disponibilidade"));
            criados.add(historico);
            monitor = new MonitorDeSites(sites, avisos, new FalasService(), historico, aprovacoes,
                    e2, props, relogio);
        }

        /** Faz o agente do PC anunciar a rotina, para ele contar como online. */
        void comRotinaNoPc(String nome) {
            e2.catalogo(List.of(new OperacoesPcService.RotinaPublica(nome, "reinicia o serviço",
                    List.of("publica"))));
        }
    }

    // ---------------------------------------------------------------- queda e volta

    @Test
    @DisplayName("uma falha sozinha não avisa: deploy curto e DNS lento não são queda")
    void umaFalhaNaoAvisa() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        c.sites.roteiro(false);
        c.monitor.checar();
        assertTrue(c.telegram.mensagens.isEmpty(),
                "a primeira falha não pode virar mensagem: " + c.telegram.mensagens);
    }

    @Test
    @DisplayName("duas falhas seguidas avisam uma vez, e a terceira não repete")
    void duasFalhasAvisamUmaVez() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        c.sites.roteiro(false, false, false);
        c.monitor.checar();
        c.monitor.checar();
        c.monitor.checar();
        assertEquals(1, c.telegram.mensagens.size(),
                "o site continuar caído não é notícia nova: " + c.telegram.mensagens);
        // 17h05 UTC = 14h05 em America/Sao_Paulo, e a hora é a da PRIMEIRA falha.
        assertEquals("O site da loja está fora do ar desde 14h05 (HTTP 502).",
                c.telegram.ultima());
    }

    @Test
    @DisplayName("a volta avisa com a duração contada da primeira falha")
    void aVoltaAvisaComDuracao() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        c.sites.roteiro(false, false, true);
        c.monitor.checar();
        c.monitor.checar();
        c.relogio.avancar(Duration.ofMinutes(25));
        c.monitor.checar();
        assertEquals(2, c.telegram.mensagens.size());
        assertEquals("O site da loja voltou. Ficou fora por 25 minutos.", c.telegram.ultima());
    }

    @Test
    @DisplayName("falha isolada seguida de sucesso não avisa nem a queda nem a volta")
    void falhaIsoladaNaoViraNada() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        c.sites.roteiro(false, true, false, true);
        for (int i = 0; i < 4; i++) {
            c.monitor.checar();
        }
        assertTrue(c.telegram.mensagens.isEmpty(), "nada caiu: " + c.telegram.mensagens);
    }

    // ---------------------------------------------------------------- silêncio

    @Test
    @DisplayName("o silêncio acumula e entrega numa mensagem só depois das 7h")
    void oSilencioAcumulaEEntregaAsSete() {
        // 05h00 UTC = 02h00 em São Paulo, dentro de 23:00-07:00.
        var c = new Cenario("2026-10-27T05:00:00Z", "23:00-07:00", "");
        c.sites.roteiro(false, false, true);
        c.monitor.checar();
        c.monitor.checar();
        c.relogio.avancar(Duration.ofMinutes(25));
        c.monitor.checar();
        assertTrue(c.telegram.mensagens.isEmpty(),
                "ninguém é acordado às duas da manhã: " + c.telegram.mensagens);
        assertEquals(2, c.avisos.acumulados(), "a queda e a volta ficaram guardadas");

        c.avisos.entregarAcumulados();
        assertTrue(c.telegram.mensagens.isEmpty(), "às 02h25 ainda é silêncio");

        // 11h00 UTC = 08h00 em São Paulo, fora da janela.
        c.relogio.avancar(Duration.ofHours(6));
        c.avisos.entregarAcumulados();
        assertEquals(1, c.telegram.mensagens.size(), "tudo numa mensagem só");
        String entregue = c.telegram.ultima();
        assertTrue(entregue.startsWith(AvisosProativos.CABECALHO_DO_ACUMULO), entregue);
        assertTrue(entregue.contains("está fora do ar desde 02h00"), entregue);
        assertTrue(entregue.contains("Ficou fora por 25 minutos"), entregue);
    }

    // ---------------------------------------------------------------- eventos de gatilho

    @Test
    @DisplayName("com SITES_ACOES, o aviso sai sem pergunta: quem oferece a rotina agora é a regra")
    void comRotinaOAvisoSaiSemPergunta() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "loja=reiniciar_api");
        c.comRotinaNoPc("reiniciar_api");
        c.sites.roteiro(false, false);
        c.monitor.checar();
        c.monitor.checar();
        assertEquals("O site da loja está fora do ar desde 14h05 (HTTP 502).", c.telegram.ultima());
        assertFalse(c.aprovacoes.pendenciaDe(DONO, "mensagem-nova").isPresent());
    }

    @Test
    @DisplayName("a queda e a volta saem como evento de gatilho, com o nome do site")
    void quedaEVoltaPublicamEvento() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        List<Object> publicados = new ArrayList<>();
        c.monitor.configurarEventos(publicados::add);
        c.sites.roteiro(false, false, false, true);
        c.monitor.checar();
        assertTrue(publicados.isEmpty(), "uma falha só não é queda");
        c.monitor.checar();
        c.monitor.checar();
        c.relogio.avancar(Duration.ofMinutes(10));
        c.monitor.checar();
        assertEquals(2, publicados.size(), "uma queda e uma volta: " + publicados);
        var caiu = (EventoDeGatilho) publicados.get(0);
        assertEquals(FonteDeGatilho.SITE_CAIU, caiu.fonte());
        assertEquals("loja", caiu.dado("site"));
        assertEquals("HTTP 502", caiu.dado("motivo"));
        var voltou = (EventoDeGatilho) publicados.get(1);
        assertEquals(FonteDeGatilho.SITE_VOLTOU, voltou.fonte());
        assertEquals("loja", voltou.dado("site"));
    }

    // ---------------------------------------------------------------- destino e histórico

    @Test
    @DisplayName("sem TELEGRAM_USUARIO_PERMITIDO ninguém é avisado")
    void semDonoNinguemEhAvisado() {
        var relogio = new RelogioFalso("2026-10-26T17:05:00Z");
        var semDono = props(0, "23:00-07:00", "");
        var telegram = new TelegramFalso(semDono);
        var sites = new SitesFalso(semDono).roteiro(false, false);
        var historico = new HistoricoDeDisponibilidade(semDono, JsonMapper.builder().build(),
                relogio, pasta.resolve("sem-dono"));
        criados.add(historico);
        var monitor = new MonitorDeSites(sites, new AvisosProativos(telegram, semDono, relogio),
                new FalasService(), historico, new Aprovacoes(relogio),
                new OperacoesPcService(JsonMapper.builder().build(),
                        pasta.resolve("op2.json"), relogio),
                semDono, relogio);
        monitor.checar();
        monitor.checar();
        assertTrue(telegram.mensagens.isEmpty());
    }

    @Test
    @DisplayName("sem site configurado a rodada não toca na rede")
    void semSiteNaoChecaNada() {
        var relogio = new RelogioFalso();
        var vazio = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("t", DONO), null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Proativo("23:00-07:00", null, 5, ""));
        var sites = new SitesFalso(vazio);
        var historico = new HistoricoDeDisponibilidade(vazio, JsonMapper.builder().build(), relogio,
                pasta.resolve("vazio"));
        criados.add(historico);
        new MonitorDeSites(sites, new AvisosProativos(new TelegramFalso(vazio), vazio, relogio),
                new FalasService(), historico, new Aprovacoes(relogio),
                new OperacoesPcService(JsonMapper.builder().build(), pasta.resolve("op3.json"),
                        relogio),
                vazio, relogio).checar();
        assertEquals(0, sites.rodadas, "nem a checagem foi pedida");
    }

    @Test
    @DisplayName("toda checagem entra no histórico, e a queda é contada uma vez")
    void oHistoricoContaChecagensEQuedas() {
        var c = new Cenario("2026-10-26T17:05:00Z", "23:00-07:00", "");
        c.sites.roteiro(true, false, false, true);
        for (int i = 0; i < 4; i++) {
            c.monitor.checar();
        }
        var serie = criados.get(criados.size() - 1).serieDiaria(1);
        assertEquals(1, serie.size());
        var loja = serie.get(0).porSite().get("loja");
        assertEquals(4, loja.checagens());
        assertEquals(2, loja.falhas());
        assertEquals(1, loja.quedas(), "a queda é um evento, não uma por checagem ruim");
        assertEquals(0.5, loja.disponibilidade(), 0.0001);
    }
}
