package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.tela.TelaEstadoService;

/** Testa a rota por onde a HUD conta o que está na tela: autenticação e validação do corpo. */
class TelaHttpTest {

    private static final String JANELA = """
            {"janelas":[{"id":"c7k2","tipo":"video","titulo":"Lo-fi para estudar",
            "estado":"normal","fixado":false,"emFoco":true,"tocando":true}],
            "historicoAberto":false,"briefingNaTela":true}""";

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null, null);

    private final TelaEstadoService tela = new TelaEstadoService();

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new TelaController(tela))
                .addFilters(new TokenHudFiltro(props)).build();
    }

    @Test
    @DisplayName("sem o token da HUD a rota recusa, inclusive com ?token= (que só vale nos GET)")
    void exigeToken() throws Exception {
        mvc().perform(post("/api/tela/estado").contentType("application/json").content(JANELA))
                .andExpect(status().isUnauthorized());
        mvc().perform(post("/api/tela/estado").param("token", "token-de-teste")
                .contentType("application/json").content(JANELA))
                .andExpect(status().isUnauthorized());

        assertTrue(tela.ultimo().isEmpty(), "nada pode ser guardado numa requisição recusada");
    }

    @Test
    @DisplayName("com token, o retrato é guardado e vira linha para o prompt")
    void guardaORetrato() throws Exception {
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content(JANELA))
                .andExpect(status().isNoContent());

        assertTrue(tela.janela("c7k2").isPresent());
        assertEquals("[c7k2] vídeo 'Lo-fi para estudar' (em foco, tocando)",
                tela.linhas().getFirst());
        assertEquals(2, tela.linhas().size(), "o briefing na tela rende a segunda linha");
    }

    @Test
    @DisplayName("janela com id, tipo ou estado fora do combinado é recusada com 400")
    void validaOCorpo() throws Exception {
        String idRuim = JANELA.replace("\"c7k2\"", "\"c7k2 com espaço\"");
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content(idRuim))
                .andExpect(status().isBadRequest());

        String tipoRuim = JANELA.replace("\"video\"", "\"carrossel\"");
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content(tipoRuim))
                .andExpect(status().isBadRequest());

        String estadoRuim = JANELA.replace("\"normal\"", "\"encolhido\"");
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content(estadoRuim))
                .andExpect(status().isBadRequest());

        assertTrue(tela.ultimo().isEmpty(), "corpo inválido não pode virar estado guardado");
    }

    @Test
    @DisplayName("respostas e mais de 24 janelas são reportadas sem truncar o retrato")
    void aceitaTodasAsJanelas() throws Exception {
        var janelas = java.util.stream.IntStream.range(0, 30)
                .mapToObj(i -> "{\"id\":\"r" + i + "\",\"tipo\":\"resposta\","
                        + "\"titulo\":\"Resposta\",\"estado\":\"minimizado\",\"fixado\":true,\"emFoco\":false,\"tocando\":false}")
                .collect(java.util.stream.Collectors.joining(","));
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"janelas\":[" + janelas + "],\"historicoAberto\":false,\"briefingNaTela\":false}"))
                .andExpect(status().isNoContent());
        assertTrue(tela.janela("r29").isPresent());
        assertEquals(12, tela.linhas().size());
        assertTrue(tela.linhas().getFirst().contains("resposta 'Resposta' (minimizada, fixada)"));
    }

    @Test
    @DisplayName("tela vazia é um retrato válido: é assim que a HUD diz que fechou tudo")
    void telaVazia() throws Exception {
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json")
                .content("{\"janelas\":[],\"historicoAberto\":false,\"briefingNaTela\":false}"))
                .andExpect(status().isNoContent());

        assertTrue(tela.ultimo().isPresent());
        assertTrue(tela.linhas().isEmpty());
    }

    @Test
    @DisplayName("o tipo 'briefing' é aceito, porque o briefing também é uma janela da HUD")
    void aceitaBriefingComoJanela() throws Exception {
        mvc().perform(post("/api/tela/estado").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json")
                .content("""
                        {"janelas":[{"id":"briefing","tipo":"briefing","titulo":"Briefing do dia",
                        "estado":"maximizado","fixado":true,"emFoco":false,"tocando":false}],
                        "historicoAberto":true,"briefingNaTela":true}"""))
                .andExpect(status().isNoContent());

        assertEquals("[briefing] briefing 'Briefing do dia' (maximizado, fixado)",
                tela.linhas().getFirst());
    }
}
