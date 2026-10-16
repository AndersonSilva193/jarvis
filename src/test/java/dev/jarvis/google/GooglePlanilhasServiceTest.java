package dev.jarvis.google;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.json.JsonMapper;

class GooglePlanilhasServiceTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();

    private JarvisProperties props(boolean configurado) {
        String chave = Base64.getEncoder().encodeToString(new byte[32]);
        var google = configurado ? new JarvisProperties.Google("cliente", "segredo",
                "https://api.exemplo.com/api/google/oauth/callback", chave, pasta.toString()) : null;
        return new JarvisProperties("A", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, google);
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> resposta(int status, String corpo) {
        HttpResponse<String> retorno = mock(HttpResponse.class);
        when(retorno.statusCode()).thenReturn(status);
        when(retorno.body()).thenReturn(corpo);
        return retorno;
    }

    @Test void semConfiguracaoOuStateValidoNaoAutoriza() throws Exception {
        var servico = new GooglePlanilhasService(mock(HttpClient.class), json, props(false));
        assertFalse(servico.configurado());
        assertThrows(Exception.class, servico::iniciar);
        var configurado = new GooglePlanilhasService(mock(HttpClient.class), json, props(true));
        assertThrows(Exception.class, () -> configurado.concluir("inexistente", "codigo"));
    }

    @Test void criaUmaVezEDevolveMesmoLinkEmRepeticao() throws Exception {
        var dados = json.readTree("{\"nome\":\"Vendas\",\"formato\":\"xlsx\",\"abas\":[{\"nome\":\"Ação\",\"colunas\":[{\"nome\":\"Código\"}],\"linhas\":[[{\"tipo\":\"texto\",\"valor\":\"0012\"}]]}]}");
        String hashDadosTeste = Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.security.MessageDigest.getInstance("SHA-256").digest(dados.toString().getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))).substring(0, 40);
        HttpClient http = mock(HttpClient.class);
        AtomicInteger criacoes = new AtomicInteger();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(chamada -> {
            HttpRequest pedido = chamada.getArgument(0);
            String caminho = pedido.uri().toString();
            if (caminho.contains("oauth2.googleapis.com/token")) {
                return resposta(200, pedido.bodyPublisher().orElseThrow().contentLength() > 0
                        && !java.nio.file.Files.exists(pasta.resolve("refresh-token.enc"))
                        ? "{\"refresh_token\":\"refresh\",\"access_token\":\"acesso\"}"
                        : "{\"access_token\":\"acesso\"}");
            }
            if (caminho.contains("drive/v3/files?q="))
                return resposta(200, criacoes.get() == 0 ? "{\"files\":[]}"
                        : "{\"files\":[{\"id\":\"planilha123\",\"appProperties\":{\"jarvisDados\":\""
                            + hashDadosTeste + "\"}}]}");
            if (caminho.contains("drive/v3/files?fields=")) {
                criacoes.incrementAndGet();
                return resposta(200, "{\"id\":\"planilha123\"}");
            }
            if (caminho.contains("?fields=sheets.properties"))
                return resposta(200, "{\"sheets\":[{\"properties\":{\"sheetId\":0}}]}");
            if (caminho.endsWith(":batchUpdate")) return resposta(200, "{}");
            throw new AssertionError(caminho);
        });
        var servico = new GooglePlanilhasService(http, json, props(true));
        String url = servico.iniciar();
        String state = java.net.URLDecoder.decode(url.substring(url.indexOf("state=") + 6),
                java.nio.charset.StandardCharsets.UTF_8);
        servico.concluir(state, "codigo");
        assertEquals("https://docs.google.com/spreadsheets/d/planilha123/edit", servico.criar(dados));
        assertEquals("https://docs.google.com/spreadsheets/d/planilha123/edit", servico.criar(dados));
        assertEquals(1, criacoes.get());
    }

    @Test void tokenRevogadoNaoAnunciaCriacao() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger tokens = new AtomicInteger();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(chamada -> {
            HttpRequest pedido = chamada.getArgument(0);
            String url = pedido.uri().toString();
            if (url.contains("oauth2.googleapis.com/token"))
                return tokens.incrementAndGet() == 1 ? resposta(200, "{\"refresh_token\":\"r\"}")
                        : resposta(400, "{}");
            throw new AssertionError(url);
        });
        var servico = new GooglePlanilhasService(http, json, props(true));
        String url = servico.iniciar();
        String state = url.substring(url.indexOf("state=") + 6);
        servico.concluir(state, "codigo");
        var dados = json.readTree("{\"nome\":\"a\",\"formato\":\"xlsx\",\"abas\":[{\"nome\":\"A\",\"colunas\":[{\"nome\":\"X\"}],\"linhas\":[]}]}");
        assertTrue(assertThrows(Exception.class, () -> servico.criar(dados))
                .getMessage().contains("expirada"));
    }

    @Test void falhaDeRedeAposPostNaoRepeteCriacaoAutomaticamente() throws Exception {
        HttpClient http = mock(HttpClient.class);
        AtomicInteger posts = new AtomicInteger();
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(chamada -> {
            HttpRequest pedido = chamada.getArgument(0);
            String url = pedido.uri().toString();
            if (url.contains("oauth2.googleapis.com/token"))
                return resposta(200, "{\"refresh_token\":\"r\",\"access_token\":\"a\"}");
            if (url.contains("drive/v3/files?q=")) return resposta(200, "{\"files\":[]}");
            if (url.contains("drive/v3/files?fields=")) {
                posts.incrementAndGet(); throw new java.io.IOException("rede caiu");
            }
            throw new AssertionError(url);
        });
        var servico = new GooglePlanilhasService(http, json, props(true));
        String url = servico.iniciar();
        servico.concluir(url.substring(url.indexOf("state=") + 6), "codigo");
        var dados = json.readTree("{\"nome\":\"a\",\"formato\":\"xlsx\",\"abas\":[{\"nome\":\"A\",\"colunas\":[{\"nome\":\"X\"}],\"linhas\":[]}]}");
        assertTrue(assertThrows(Exception.class, () -> servico.criar(dados))
                .getMessage().contains("incerta"));
        assertEquals(1, posts.get());
    }
}
