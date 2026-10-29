package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.holograma.ExemplosDeHolograma;
import dev.jarvis.holograma.HologramaLineParser;
import tools.jackson.databind.json.JsonMapper;

/** O exemplo pelo SSE, atrás do token da HUD: meta primeiro, as peças, e o fim fechando. */
class HologramaHttpTest {

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null, null);
    private final HologramaController controller = new HologramaController(new ExemplosDeHolograma(),
            new HologramaLineParser(JsonMapper.builder().build()), Duration.ZERO);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new TokenHudFiltro(props)).build();

    @AfterEach
    void fechar() {
        controller.fechar();
    }

    @Test
    void semTokenNemQueryToken() throws Exception {
        mvc.perform(get("/api/holograma/exemplo/atomo")).andExpect(status().isUnauthorized());
        // O ?token= é exceção do SSE geral, de anexos e de áudio — não desta rota.
        mvc.perform(get("/api/holograma/exemplo/atomo").param("token", "token-de-teste"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exemploQueNaoExiste() throws Exception {
        mvc.perform(get("/api/holograma/exemplo/nao_existe").header("Authorization", "Bearer token-de-teste"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transmiteOExemploEmOrdem() throws Exception {
        var resultado = mvc.perform(get("/api/holograma/exemplo/atomo")
                .header("Authorization", "Bearer token-de-teste"))
                .andExpect(request().asyncStarted()).andReturn();

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!resultado.getResponse().getContentAsString(StandardCharsets.UTF_8).contains("event:fim")) {
                Thread.sleep(5);
            }
        });
        String corpo = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(corpo.startsWith("event:meta"), corpo);
        assertTrue(corpo.contains("\"nome\":\"Átomo de carbono\""), corpo);
        assertTrue(corpo.contains("\"id\":\"orbita_2\""), corpo);
        assertTrue(!corpo.contains("event:aviso"), corpo);

        int pecas = corpo.split("event:peca", -1).length - 1;
        assertTrue(corpo.contains("\"pecas\":" + pecas + ",\"avisos\":0"), corpo);
        // 4 peças soltas, 2 grupos de 3 órbitas e 6 grupos de 3 partículas, mais o campo do núcleo.
        assertEquals(28, pecas);
    }
}
