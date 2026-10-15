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
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O monitor é o que impede a cota de acabar no meio de um briefing. Nada aqui toca a rede: o
 * saldo vem de um JSON fixo e a ElevenLabs é um mock.
 */
class MonitorDeCotaVozTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long CHAT = 42;

    private final ElevenLabsVoz elevenLabs = mock(ElevenLabsVoz.class);
    private final TelegramClient telegram = mock(TelegramClient.class);

    private static JarvisProperties props(int reservaPct) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", CHAT), null, null, null, null, null,
                new JarvisProperties.Voz("elevenlabs", "chave", "voz-1", "eleven_flash_v2_5", reservaPct), null, null);
    }

    private MonitorDeCotaVoz monitor(int reservaPct) {
        JarvisProperties props = props(reservaPct);
        return new MonitorDeCotaVoz(elevenLabs, telegram, new DiarioDeVoz(props), props,
                Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC));
    }

    private static SaldoDeVoz saldo(long usados, long limite) {
        return new SaldoDeVoz(usados, limite, Instant.parse("2026-02-01T00:00:00Z"));
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
        var monitor = monitor(0);
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
                new JarvisProperties.Voz("elevenlabs", "", "", "eleven_flash_v2_5", 5), null, null);
        var monitor = new MonitorDeCotaVoz(elevenLabs, telegram, new DiarioDeVoz(props), props,
                Clock.systemUTC());

        monitor.consultarSaldo();

        assertFalse(monitor.remotaAtiva());
        assertEquals(MonitorDeCotaVoz.Motivo.DESLIGADO, monitor.motivo());
        verify(elevenLabs, never()).consultarSaldo();
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }
}
