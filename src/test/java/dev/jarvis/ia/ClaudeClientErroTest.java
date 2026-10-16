package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.json.JsonMapper;

class ClaudeClientErroTest {
    @Test void motivoDoHttp400EExtraidoSemExporChave() throws Exception {
        HttpClient http = mock(HttpClient.class);
        @SuppressWarnings("unchecked")
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(400);
        when(resposta.body()).thenReturn("""
                {"type":"error","error":{"type":"invalid_request_error",
                "message":"messages.3.content: token Bearer abc123 e sk-ant-segredo inválido"}}
                """);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(resposta);
        var props = new JarvisProperties("Jarvis", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "modelo", 1024, 512),
                null, null, null, null, null, null, null, null, null);
        var json = JsonMapper.builder().build();
        var cliente = new ClaudeClient(http, json, props);
        var erro = assertThrows(ErroDaApiDoModelo.class, () -> cliente.criarMensagem(
                json.createArrayNode(), json.createArrayNode(), json.createArrayNode()));
        assertEquals(400, erro.status());
        assertTrue(erro.detalhe().contains("messages.3.content"));
        assertFalse(erro.detalhe().contains("abc123"));
        assertFalse(erro.detalhe().contains("sk-ant-segredo"));
    }
}
