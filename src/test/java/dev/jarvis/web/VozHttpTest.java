package dev.jarvis.web;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.voz.VozService;

/**
 * O contrato que a HUD enxerga: a síntese sempre responde algo tocável ou algo falável, e o MP3
 * aceita ?token= porque o elemento &lt;audio&gt; não manda header.
 */
class VozHttpTest {

    private static final String ID = "a".repeat(64);

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null);
    private final VozService voz = mock(VozService.class);

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new VozController(voz))
                .addFilters(new TokenHudFiltro(props)).build();
    }

    @Test
    @DisplayName("a síntese devolve a rota do MP3, ou manda a HUD falar sozinha")
    void sintetiza() throws Exception {
        var mvc = mvc();
        when(voz.preparar("Bom dia, senhor.")).thenReturn(VozService.Fala.daElevenLabs(ID));
        when(voz.preparar("Boa noite.")).thenReturn(VozService.Fala.paraNavegador("reserva"));

        mvc.perform(post("/api/voz/sintetizar").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\"Bom dia, senhor.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value("elevenlabs"))
                .andExpect(jsonPath("$.url").value("/api/voz/audio/" + ID));

        mvc.perform(post("/api/voz/sintetizar").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\"Boa noite.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value("navegador"))
                .andExpect(jsonPath("$.motivo").value("reserva"));
    }

    @Test
    @DisplayName("texto vazio é erro de pedido, não uma fala muda")
    void textoVazio() throws Exception {
        mvc().perform(post("/api/voz/sintetizar").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\"   \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("o MP3 aceita ?token=, a síntese não")
    void tokenNaQuerySoNoAudio() throws Exception {
        var mvc = mvc();
        when(voz.audio(ID)).thenReturn(Optional.of(new byte[] { 1, 2, 3 }));
        when(voz.preparar(anyString())).thenReturn(VozService.Fala.paraNavegador("desligado"));

        mvc.perform(get("/api/voz/audio/" + ID).param("token", "token-de-teste"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));

        mvc.perform(get("/api/voz/audio/" + ID))
                .andExpect(status().isUnauthorized());

        // O POST continua exigindo o header, mesmo com o token certo na query.
        mvc.perform(post("/api/voz/sintetizar").param("token", "token-de-teste")
                .contentType("application/json").content("{\"texto\":\"oi\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("áudio que não está no cache é 404, não erro")
    void audioInexistente() throws Exception {
        when(voz.audio(ID)).thenReturn(Optional.empty());

        mvc().perform(get("/api/voz/audio/" + ID).param("token", "token-de-teste"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("o status mostra provedor, consumo e renovação")
    void mostraStatusDaVoz() throws Exception {
        when(voz.status()).thenReturn(new VozService.Status("elevenlabs", 18_000, 30_000, 60,
                "2026-02-01T00:00-03:00[America/Sao_Paulo]", null, null));

        mvc().perform(get("/api/voz/status").header("Authorization", "Bearer token-de-teste"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provedor").value("elevenlabs"))
                .andExpect(jsonPath("$.percentual").value(60))
                .andExpect(jsonPath("$.caracteresUsados").value(18_000));
    }
}
