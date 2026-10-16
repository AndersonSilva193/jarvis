package dev.jarvis.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.inicializacao.InicializacaoService;
import dev.jarvis.inicializacao.Introducao;

/** A rota que a HUD chama ao acender: o token exigido e o formato que ela lê. */
class InicializacaoHttpTest {

    private static final String HUD_TOKEN = "token-da-hud";
    private static final String PC_TOKEN = "token-do-pc";

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud(HUD_TOKEN, ""), null, null, null, null,
            new JarvisProperties.Pc(PC_TOKEN));

    private final InicializacaoService inicializacao = mock(InicializacaoService.class);

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new InicializacaoController(inicializacao))
                .addFilters(new TokenPcFiltro(props), new TokenHudFiltro(props))
                .build();
    }

    @Test
    @DisplayName("com o token da HUD devolve o texto e os números da abertura")
    void devolveAIntroducao() throws Exception {
        when(inicializacao.gerar()).thenReturn(new Introducao(
                "Boa tarde, senhor. Às suas ordens.", 3, 3, true, 2, "2026-10-15T14:00-03:00"));

        mvc().perform(post("/api/inicializacao")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + HUD_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.texto").value("Boa tarde, senhor. Às suas ordens."))
                .andExpect(jsonPath("$.sitesOk").value(3))
                .andExpect(jsonPath("$.sitesTotal").value(3))
                .andExpect(jsonPath("$.climaOk").value(true))
                .andExpect(jsonPath("$.lembretesHoje").value(2))
                // Data como texto ISO-8601: o JsonMapper do BeansConfig é cru.
                .andExpect(jsonPath("$.geradoEm").value("2026-10-15T14:00-03:00"));
    }

    @Test
    @DisplayName("sem token, com token errado ou com o do PC é 401 e nada é gerado")
    void exigeOTokenDaHud() throws Exception {
        mvc().perform(post("/api/inicializacao")).andExpect(status().isUnauthorized());
        mvc().perform(post("/api/inicializacao")
                .header(HttpHeaders.AUTHORIZATION, "Bearer nao-e-o-token"))
                .andExpect(status().isUnauthorized());
        // A rota não é do PC, então o PC_TOKEN não abre esta porta.
        mvc().perform(post("/api/inicializacao")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + PC_TOKEN))
                .andExpect(status().isUnauthorized());

        org.mockito.Mockito.verifyNoInteractions(inicializacao);
    }
}
