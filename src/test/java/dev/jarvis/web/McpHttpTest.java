package dev.jarvis.web;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.financas.FinancasService;
import dev.jarvis.mcp.ResumoFinanceiroMcp;
import dev.jarvis.mcp.ServidorMcp;
import dev.jarvis.inicializacao.InicializacaoService;
import tools.jackson.databind.json.JsonMapper;

/** A porta do MCP: o token dela, separado dos outros dois, e as respostas HTTP do transporte. */
class McpHttpTest {

    private static final String HUD_TOKEN = "token-da-hud";
    private static final String PC_TOKEN = "token-do-pc";
    private static final String MCP_TOKEN = "token-do-mcp";

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud(HUD_TOKEN, "", MCP_TOKEN), null, null, null, null,
            new JarvisProperties.Pc(PC_TOKEN));

    private final JsonMapper json = JsonMapper.builder().build();

    private MockMvc mvc() {
        var servidor = new ServidorMcp(json,
                List.of(new ResumoFinanceiroMcp(mock(FinancasService.class), json)));
        return MockMvcBuilders.standaloneSetup(new McpController(servidor),
                        new InicializacaoController(mock(InicializacaoService.class)))
                .addFilters(new TokenPcFiltro(props), new TokenMcpFiltro(props),
                        new TokenHudFiltro(props))
                .build();
    }

    private static final String PING = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

    @Test
    @DisplayName("só o MCP_TOKEN abre /api/mcp; o da HUD e o do PC não")
    void exigeOTokenProprio() throws Exception {
        var mvc = mvc();
        mvc.perform(post("/api/mcp").contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + HUD_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + PC_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer " + MCP_TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result").isMap());
    }

    @Test
    @DisplayName("o MCP_TOKEN não abre as rotas da HUD")
    void naoAbreAHud() throws Exception {
        mvc().perform(post("/api/inicializacao")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + MCP_TOKEN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("notificação é 202, Origin é 403, GET e DELETE são 405")
    void transporte() throws Exception {
        var mvc = mvc();
        String autorizacao = "Bearer " + MCP_TOKEN;
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, autorizacao)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, autorizacao)
                .header(HttpHeaders.ORIGIN, "https://pagina-qualquer.example")
                .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/mcp").header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/mcp").header(HttpHeaders.AUTHORIZATION, autorizacao))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("sem MCP_TOKEN configurado, nada entra")
    void semTokenNadaEntra() throws Exception {
        var semMcp = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("teste", 42), null, null, null,
                new JarvisProperties.Hud(HUD_TOKEN, ""), null, null, null, null,
                new JarvisProperties.Pc(PC_TOKEN));
        var servidor = new ServidorMcp(json, List.of());
        MockMvcBuilders.standaloneSetup(new McpController(servidor))
                .addFilters(new TokenMcpFiltro(semMcp)).build()
                .perform(post("/api/mcp").header(HttpHeaders.AUTHORIZATION, "Bearer ")
                        .contentType(MediaType.APPLICATION_JSON).content(PING))
                .andExpect(status().isUnauthorized());
    }
}
