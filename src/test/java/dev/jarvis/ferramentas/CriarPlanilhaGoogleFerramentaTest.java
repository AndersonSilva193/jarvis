package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.google.GooglePlanilhasService;
import dev.jarvis.pc.PedidoArquivo;
import tools.jackson.databind.json.JsonMapper;

class CriarPlanilhaGoogleFerramentaTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void googleAusenteAfetaSomentePlanilhaOnline() throws Exception {
        HttpClient http = mock(HttpClient.class);
        var propriedades = new JarvisProperties("Jarvis", "America/Sao_Paulo", "", null,
                null, null, null, null, null, null, null, null, null, null);
        var google = new GooglePlanilhasService(http, json, propriedades);
        var ferramenta = new CriarPlanilhaGoogleFerramenta(google, json);
        var planilha = json.readTree("""
                {"nome":"Vendas","formato":"xlsx","pesquisaAtual":true,
                 "fontes":[{"titulo":"Fonte","url":"https://exemplo.com/dados","consultadoEm":"2026-10-16"}],"abas":[
                  {"nome":"Dados","colunas":[{"nome":"Código"}],"linhas":[]}]}
                """);

        assertFalse(google.configurado());
        assertTrue(ferramenta.esquemaEntrada().path("properties").path("abas").isMissingNode());
        assertEquals("", PedidoArquivo.validar(planilha));
        assertTrue(ferramenta.executarDetalhado(planilha, new OuvinteProgresso() { })
                .texto().contains("integração Google Sheets não configurada"));
        assertTrue(ferramenta.executar(planilha).contains("planilha online não criada"));
        verifyNoInteractions(http);
    }

    @Test void chaveMalformadaNaoHabilitaIntegracao() {
        var propriedades = new JarvisProperties("Jarvis", "America/Sao_Paulo", "", null,
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Google("cliente", "segredo", "https://exemplo.com/callback",
                        "chave-inválida", "/data/google"));
        var google = new GooglePlanilhasService(mock(HttpClient.class), json, propriedades);
        assertFalse(google.configurado());
        assertFalse(google.autorizado());
    }
}
