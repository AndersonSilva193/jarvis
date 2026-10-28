package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.BancadaLocal;
import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.ModoDeIaEvento;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.proativo.FalasService;

/** GET e POST de /api/ia/modo atrás do token da HUD, e o ia.modo chegando pelo SSE. */
class IaModoHttpTest {

    @TempDir Path pasta;
    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null, null);

    @Test void modoPeloHttpEEventoPeloSse() throws Exception {
        var eventos = new EventosHud(props);
        try (var bancada = new BancadaLocal(pasta)) {
            var seletor = new SeletorDeModelo(bancada.disponibilidade, bancada.props, BancadaLocal.JSON,
                    evento -> { if (evento instanceof ModoDeIaEvento e) eventos.aoModoDeIa(e); },
                    null, new FalasService(), null, Clock.systemUTC(), pasta.resolve("modo-ia.json"));
            var mvc = MockMvcBuilders.standaloneSetup(new IaController(seletor), new EventosController(eventos))
                    .addFilters(new TokenHudFiltro(props)).build();
            String token = "Bearer token-de-teste";

            mvc.perform(get("/api/ia/modo")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/ia/modo").header("Authorization", token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modo").value("auto"))
                    .andExpect(jsonPath("$.provedor").value("local"))
                    .andExpect(jsonPath("$.modelo").value("qwen3.5:9b"))
                    .andExpect(jsonPath("$.motivo").value(""));

            var sse = mvc.perform(get("/api/eventos").param("token", "token-de-teste"))
                    .andExpect(request().asyncStarted()).andReturn();

            mvc.perform(post("/api/ia/modo").header("Authorization", token)
                    .contentType("application/json").content("{\"modo\":\"Claude\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.modo").value("claude"))
                    .andExpect(jsonPath("$.provedor").value("claude"));
            assertEquals(ModoDeIa.CLAUDE, seletor.modo());

            mvc.perform(post("/api/ia/modo").header("Authorization", token)
                    .contentType("application/json").content("{\"modo\":\"turbo\"}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/ia/modo").header("Authorization", token)
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest());

            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!sse.getResponse().getContentAsString(StandardCharsets.UTF_8)
                        .contains("event:ia.modo")) Thread.sleep(5);
            });
            String dados = sse.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertEquals(true, dados.contains("\"modo\":\"claude\""), dados);
        } finally { eventos.fechar(); }
    }
}
