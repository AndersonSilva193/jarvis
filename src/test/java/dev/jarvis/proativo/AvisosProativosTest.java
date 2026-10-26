package dev.jarvis.proativo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;

/** O portão dos avisos não pedidos: silêncio, repetição e destino. */
class AvisosProativosTest {

    private static JarvisProperties props(long usuario, String silencio) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("t", usuario), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Proativo(silencio, null, 5, ""));
    }

    @Test
    @DisplayName("o mesmo estado não é anunciado duas vezes")
    void mesmoEstadoNaoRepete() {
        var props = props(1L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var avisos = new AvisosProativos(telegram, props, new RelogioFalso("2026-10-26T17:00:00Z"));

        assertEquals(AvisosProativos.Entrega.ENVIADO, avisos.avisar("site:loja", "fora", "caiu", null));
        assertEquals(AvisosProativos.Entrega.REPETIDO, avisos.avisar("site:loja", "fora", "caiu", null));
        assertEquals(AvisosProativos.Entrega.ENVIADO, avisos.avisar("site:loja", "dentro", "voltou", null));
        assertEquals(2, telegram.mensagens.size());
    }

    @Test
    @DisplayName("chaves diferentes não se atrapalham")
    void chavesSaoIndependentes() {
        var props = props(1L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var avisos = new AvisosProativos(telegram, props, new RelogioFalso("2026-10-26T17:00:00Z"));
        avisos.avisar("site:loja", "fora", "a loja caiu", null);
        avisos.avisar("site:api", "fora", "a api caiu", null);
        assertEquals(2, telegram.mensagens.size());
    }

    @Test
    @DisplayName("a janela que cruza a meia-noite cobre as duas metades da noite")
    void aJanelaCruzaAMeiaNoite() {
        var janela = JanelaDeSilencio.de("23:00-07:00");
        var sp = java.time.ZoneId.of("America/Sao_Paulo");
        // 23h30 e 00h30 estão dentro; 07h00 e 14h00 estão fora.
        assertTrue(janela.contem(java.time.Instant.parse("2026-10-27T02:30:00Z"), sp), "23h30");
        assertTrue(janela.contem(java.time.Instant.parse("2026-10-27T03:30:00Z"), sp), "00h30");
        assertFalse(janela.contem(java.time.Instant.parse("2026-10-27T10:00:00Z"), sp), "07h00");
        assertFalse(janela.contem(java.time.Instant.parse("2026-10-27T17:00:00Z"), sp), "14h00");
    }

    @Test
    @DisplayName("janela de largura zero é silêncio desligado")
    void janelaDeLarguraZeroNaoCala() {
        var props = props(1L, "07:00-07:00");
        var telegram = new TelegramFalso(props);
        var avisos = new AvisosProativos(telegram, props, new RelogioFalso("2026-10-27T05:00:00Z"));
        assertEquals(AvisosProativos.Entrega.ENVIADO, avisos.avisar("x", "a", "às duas da manhã", null));
    }

    @Test
    @DisplayName("faixa torta no .env não derruba nada e cai no padrão da madrugada")
    void faixaTortaCaiNoPadrao() {
        assertEquals(JanelaDeSilencio.PADRAO, JanelaDeSilencio.de("das onze às sete"));
        assertEquals(JanelaDeSilencio.PADRAO, JanelaDeSilencio.de("23:00"));
        assertEquals(JanelaDeSilencio.PADRAO, JanelaDeSilencio.de(""));
        assertEquals(JanelaDeSilencio.PADRAO, JanelaDeSilencio.de(null));
    }

    @Test
    @DisplayName("a pergunta não é avaliada durante o silêncio")
    void aPerguntaNaoNasceNoSilencio() {
        var props = props(1L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var relogio = new RelogioFalso("2026-10-27T05:00:00Z");
        var avisos = new AvisosProativos(telegram, props, relogio);
        var avaliacoes = new AtomicInteger();

        assertEquals(AvisosProativos.Entrega.ACUMULADO,
                avisos.avisar("site:loja", "fora", "caiu", () -> {
                    avaliacoes.incrementAndGet();
                    return "Posso?";
                }));
        assertEquals(0, avaliacoes.get(), "pendência criada às 2h expiraria antes de ser lida");

        relogio.avancar(Duration.ofHours(6));
        avisos.entregarAcumulados();
        assertEquals(1, telegram.mensagens.size());
        assertFalse(telegram.ultima().contains("Posso?"),
                "o que foi acumulado não ganha pergunta na entrega");
    }

    @Test
    @DisplayName("acima do teto a fila conta o que não couber em vez de crescer sem fim")
    void acimaDoTetoAFilaConta() {
        var props = props(1L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var relogio = new RelogioFalso("2026-10-27T05:00:00Z");
        var avisos = new AvisosProativos(telegram, props, relogio);
        for (int i = 0; i < AvisosProativos.MAX_ACUMULADOS + 3; i++) {
            avisos.avisar("evento:" + i, "novo", "aviso " + i, null);
        }
        assertEquals(AvisosProativos.MAX_ACUMULADOS, avisos.acumulados());

        relogio.avancar(Duration.ofHours(6));
        avisos.entregarAcumulados();
        assertTrue(telegram.ultima().contains("e mais 3 aviso(s) que não couberam."),
                telegram.ultima());
    }

    @Test
    @DisplayName("fila vazia não manda mensagem nenhuma")
    void filaVaziaNaoMandaNada() {
        var props = props(1L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var avisos = new AvisosProativos(telegram, props, new RelogioFalso("2026-10-26T17:00:00Z"));
        avisos.entregarAcumulados();
        assertTrue(telegram.mensagens.isEmpty());
    }

    @Test
    @DisplayName("sem destino configurado nada é enviado nem acumulado")
    void semDestinoNaoAcumula() {
        var props = props(0L, "23:00-07:00");
        var telegram = new TelegramFalso(props);
        var avisos = new AvisosProativos(telegram, props, new RelogioFalso("2026-10-27T05:00:00Z"));
        assertEquals(AvisosProativos.Entrega.SEM_DESTINO, avisos.avisar("x", "a", "b", null));
        assertEquals(0, avisos.acumulados(), "guardar para ninguém é só memória gasta");
    }
}
