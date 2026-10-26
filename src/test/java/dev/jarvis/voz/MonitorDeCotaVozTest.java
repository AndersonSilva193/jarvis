package dev.jarvis.voz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O monitor é o que impede a cota de acabar no meio de um briefing. Nada aqui toca a rede: o
 * saldo vem de um JSON fixo e a ElevenLabs é um mock.
 *
 * <p>O {@code AvisosProativos} e o {@code FalasService} são os de verdade, não duplos: o que se
 * quer provar é justamente que o aviso passa por eles — com o horário de silêncio e a memória de
 * não repetir. O {@code TelegramClient} é o único mock do caminho de saída.
 */
class MonitorDeCotaVozTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CHAT = 42;

    /** 09h em São Paulo: fora do horário de silêncio, que é o caso normal. */
    private static final String MANHA = "2026-01-10T12:00:00Z";

    /** 02h em São Paulo: dentro do silêncio. */
    private static final String MADRUGADA = "2026-01-11T05:00:00Z";

    @TempDir
    Path pasta;

    private final ElevenLabsVoz elevenLabs = mock(ElevenLabsVoz.class);
    private final TelegramClient telegram = mock(TelegramClient.class);

    private RelogioFalso relogio = new RelogioFalso(MANHA);
    private AvisosProativos avisos;
    private DiarioDeVoz diario;

    private static JarvisProperties props(int reservaPct, int avisoPct) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", CHAT), null, null, null, null, null,
                new JarvisProperties.Voz("elevenlabs", "chave", "voz-1", "eleven_flash_v2_5",
                        reservaPct, avisoPct),
                null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Proativo("23:00-07:00", null, 5, ""));
    }

    private MonitorDeCotaVoz monitor(int reservaPct) {
        return monitor(reservaPct, 80);
    }

    private MonitorDeCotaVoz monitor(int reservaPct, int avisoPct) {
        JarvisProperties props = props(reservaPct, avisoPct);
        avisos = new AvisosProativos(telegram, props, relogio);
        diario = new DiarioDeVoz(props, JSON, relogio, pasta);
        return new MonitorDeCotaVoz(elevenLabs, avisos, new FalasService(), diario, props, relogio);
    }

    private static SaldoDeVoz saldo(long usados, long limite) {
        return new SaldoDeVoz(usados, limite, Instant.parse("2026-02-01T00:00:00Z"));
    }

    /** Dois dias completos de uso, para a projeção ter ritmo: o relógio anda de verdade. */
    private void doisDiasDeUso(int caracteresPorDia) {
        diario.sintetizou(caracteresPorDia);
        relogio.avancar(Duration.ofDays(1));
        diario.sintetizou(caracteresPorDia);
        relogio.avancar(Duration.ofDays(1));
    }

    private String mensagemEnviada() {
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(telegram).enviarMensagem(eq(CHAT), texto.capture());
        return texto.getValue();
    }

    @Test
    @DisplayName("lê caracteres usados, limite e renovação da resposta da ElevenLabs")
    void interpretaORetornoDeSaldo() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/voz-subscription.json")) {
            JsonNode raiz = JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));

            SaldoDeVoz saldo = ElevenLabsVoz.interpretar(raiz);

            assertEquals(18000, saldo.usados());
            assertEquals(30000, saldo.limite());
            assertEquals(Instant.ofEpochSecond(1738356858), saldo.renovaEm());
            assertEquals(60, saldo.percentualUsado());
            assertEquals(12000, saldo.restante());
        }
    }

    @Test
    @DisplayName("reclama em vez de inventar saldo quando falta o limite na resposta")
    void saldoSemLimiteFalha() {
        var raiz = JSON.createObjectNode().put("character_count", 10);
        var erro = org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> ElevenLabsVoz.interpretar(raiz));
        assertTrue(erro.getMessage().contains("character_limit"), erro.getMessage());
    }

    @Test
    @DisplayName("chegando à reserva, volta à voz do navegador e avisa no Telegram")
    void trocaNaReserva() throws Exception {
        var monitor = monitor(5);
        // Restam 1000 de 30000; a reserva é 1500.
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(29_000, 30_000));

        monitor.consultarSaldo();

        assertFalse(monitor.remotaAtiva());
        assertEquals(MonitorDeCotaVoz.Motivo.RESERVA, monitor.motivo());
        verify(telegram).enviarMensagem(eq(CHAT), contains("voz do navegador até 31/01"));
    }

    @Test
    @DisplayName("o aviso da troca sai uma vez só, não a cada dez minutos")
    void avisaUmaVezSo() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(29_000, 30_000));

        monitor.consultarSaldo();
        monitor.consultarSaldo();
        monitor.consultarSaldo();

        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("já no navegador, o aviso antecipado não repete o que a reserva já disse")
    void reservaNaoGanhaAvisoAntecipadoPorCima() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(29_000, 30_000));

        monitor.consultarSaldo();

        // 96% está muito acima dos 80%, mas a voz já caiu para o navegador: um aviso só.
        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());
        String texto = mensagemEnviada();
        assertTrue(texto.contains("chegou à reserva"), texto);
    }

    @Test
    @DisplayName("erro de cota da ElevenLabs troca para o navegador na hora")
    void trocaNoErroDeCota() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo())
                .thenThrow(new CotaDeVozException("cota", "HTTP 401 quota_exceeded"));

        monitor.consultarSaldo();

        assertFalse(monitor.remotaAtiva());
        assertEquals(MonitorDeCotaVoz.Motivo.COTA, monitor.motivo());
        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("chave recusada não promete data de volta que não existe")
    void avisoDeChaveNaoPrometeData() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo())
                .thenThrow(new CotaDeVozException("chave", "HTTP 401 invalid_api_key"));

        monitor.consultarSaldo();

        assertEquals(MonitorDeCotaVoz.Motivo.CHAVE, monitor.motivo());
        verify(telegram).enviarMensagem(eq(CHAT), contains("chave da ElevenLabs foi recusada"));
    }

    @Test
    @DisplayName("texto maior que o saldo não é nem tentado")
    void textoMaiorQueOSaldo() throws Exception {
        var monitor = monitor(0, 0);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(29_000, 30_000));
        monitor.consultarSaldo();

        assertTrue(monitor.remotaAtiva(), "com reserva zero e 1000 sobrando, ainda dá para falar");
        assertTrue(monitor.podeSintetizar(500));
        assertFalse(monitor.podeSintetizar(2_000), "2000 caracteres não cabem em 1000 de saldo");
        assertFalse(monitor.remotaAtiva());
    }

    @Test
    @DisplayName("o gasto entre duas consultas conta, senão um briefing fura a reserva")
    void contaOGastoLocal() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(20_000, 30_000));
        monitor.consultarSaldo();
        assertEquals(10_000, monitor.restante());

        monitor.registrarUso(4_000);

        assertEquals(6_000, monitor.restante());
        assertTrue(monitor.remotaAtiva());

        monitor.registrarUso(5_000);

        assertEquals(1_000, monitor.restante());
        assertFalse(monitor.remotaAtiva(), "1000 está abaixo da reserva de 1500");
    }

    @Test
    @DisplayName("depois da renovação, a consulta seguinte traz a voz de volta e avisa")
    void voltaDepoisDaRenovacao() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo())
                .thenReturn(saldo(29_000, 30_000))
                .thenReturn(new SaldoDeVoz(0, 30_000, Instant.parse("2026-03-01T00:00:00Z")));

        monitor.consultarSaldo();
        assertFalse(monitor.remotaAtiva());

        monitor.consultarSaldo();

        assertTrue(monitor.remotaAtiva());
        assertNull(monitor.motivo());
        verify(telegram).enviarMensagem(eq(CHAT), contains("renovada"));
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("ElevenLabs fora do ar não troca o modo nem avisa ninguém")
    void foraDoArNaoTrocaOModo() throws Exception {
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(1_000, 30_000));
        monitor.consultarSaldo();

        when(elevenLabs.consultarSaldo()).thenThrow(new IOException("connection reset"));
        monitor.consultarSaldo();

        assertTrue(monitor.remotaAtiva(), "queda de rede desvia a fala, não o modo");
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("sem chave configurada o monitor nasce desligado e não chama a ElevenLabs")
    void desligadoSemConfiguracao() throws Exception {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", CHAT), null, null, null, null, null,
                new JarvisProperties.Voz("elevenlabs", "", "", "eleven_flash_v2_5", 5), null, null,
                null);
        var monitor = new MonitorDeCotaVoz(elevenLabs, new AvisosProativos(telegram, props),
                new FalasService(), new DiarioDeVoz(props, JSON, relogio, pasta), props, relogio);

        monitor.consultarSaldo();

        assertFalse(monitor.remotaAtiva());
        assertEquals(MonitorDeCotaVoz.Motivo.DESLIGADO, monitor.motivo());
        verify(elevenLabs, never()).consultarSaldo();
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }

    // ---------------------------------------------------------------- aviso antecipado

    @Test
    @DisplayName("o aviso de 80% sai uma vez por período de cobrança, e volta no período seguinte")
    void avisoDe80SaiUmaVezPorPeriodo() throws Exception {
        var monitor = monitor(5);
        // 25 500 de 30 000 = 85%, e sobram 4500 contra uma reserva de 1500: a voz segue na
        // ElevenLabs, que é justamente o caso em que o aviso antecipado tem o que fazer.
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();
        monitor.consultarSaldo();
        monitor.consultarSaldo();

        assertTrue(monitor.remotaAtiva(), "85% não desliga a voz da ElevenLabs");
        verify(telegram, times(1)).enviarMensagem(eq(CHAT), contains("cota de voz está em 85%"));

        // Período seguinte: outra data de renovação, o mesmo 85%.
        when(elevenLabs.consultarSaldo())
                .thenReturn(new SaldoDeVoz(25_500, 30_000, Instant.parse("2026-03-01T00:00:00Z")));

        monitor.consultarSaldo();

        verify(telegram, times(2)).enviarMensagem(eq(CHAT), contains("cota de voz está em 85%"));
    }

    @Test
    @DisplayName("com uso de dois dias, o aviso projeta até quando a cota dura")
    void projecaoComDados() throws Exception {
        var monitor = monitor(5);
        // 2000 por dia e 4500 sobrando dão dois dias inteiros: 12/01 + 2.
        doisDiasDeUso(2_000);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();

        assertTrue(mensagemEnviada().contains("No ritmo atual, dura até 14/01"), mensagemEnviada());
    }

    @Test
    @DisplayName("sem dias completos de uso, o aviso sai sem projeção em vez de chutar data")
    void projecaoOmitidaSemDados() throws Exception {
        var monitor = monitor(5);
        // Só o uso de hoje, que é um dia pela metade: não há ritmo para afirmar.
        diario.sintetizou(2_000);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();

        String texto = mensagemEnviada();
        assertEquals("A cota de voz está em 85%.", texto);
        assertFalse(texto.contains("ritmo"), texto);
    }

    @Test
    @DisplayName("projeção que passa da renovação diz que a cota chega lá, não uma data inútil")
    void projecaoAlemDaRenovacaoNaoPrometeData() throws Exception {
        var monitor = monitor(5);
        // 100 por dia: os 4500 que sobram passam longe da renovação. A data falada é 31/01 porque
        // a meia-noite UTC de 01/02 é 21h do dia 31 em São Paulo — o mesmo dia que o aviso da
        // reserva já diz.
        doisDiasDeUso(100);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();

        String texto = mensagemEnviada();
        assertTrue(texto.contains("chega à renovação de 31/01 sem acabar"), texto);
    }

    @Test
    @DisplayName("aviso que nasce na madrugada fica na fila e é entregue depois das sete")
    void avisoNoSilencioSaiDepoisDasSete() throws Exception {
        relogio = new RelogioFalso(MADRUGADA);
        var monitor = monitor(5);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();

        verify(telegram, never()).enviarMensagem(anyLong(), anyString());

        // 10h em São Paulo: fora do silêncio. O tique de minuto é que entrega o acúmulo.
        relogio.avancar(Duration.ofHours(8));
        avisos.entregarAcumulados();

        String texto = mensagemEnviada();
        assertTrue(texto.startsWith("Enquanto o senhor dormia:"), texto);
        assertTrue(texto.contains("A cota de voz está em 85%"), texto);
    }

    @Test
    @DisplayName("VOZ_AVISO_PERCENTUAL em zero desliga o aviso antecipado, não o da reserva")
    void avisoAntecipadoDesligado() throws Exception {
        var monitor = monitor(5, 0);
        when(elevenLabs.consultarSaldo()).thenReturn(saldo(25_500, 30_000));

        monitor.consultarSaldo();

        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }
}
