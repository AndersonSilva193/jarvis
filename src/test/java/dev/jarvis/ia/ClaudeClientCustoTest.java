package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import tools.jackson.databind.json.JsonMapper;

/**
 * A fiação entre o {@code ClaudeClient} e o diário de custo.
 *
 * É este teste que sustenta a decisão de a {@link Origem} ser parâmetro obrigatório: ele fixa que
 * cada origem chega ao medidor como ela mesma, e que uma chamada repetida pela política de
 * tentativas conta <b>uma vez</b>.
 */
@SuppressWarnings("unchecked")
class ClaudeClientCustoTest {

    private static final String USO = """
            {"usage":{"input_tokens":812,"output_tokens":96,
             "cache_creation_input_tokens":4096,"cache_read_input_tokens":2310,
             "server_tool_use":{"web_search_requests":3}},"content":[]}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private final java.net.http.HttpClient http = mock(java.net.http.HttpClient.class);
    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);

    private final JarvisProperties props = new JarvisProperties("Jarvis", "America/Sao_Paulo", "",
            null, new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512),
            null, null, null, null, null, null, null, null);

    private ClaudeClient cliente() {
        return new ClaudeClient(http, json, props, medidor);
    }

    /**
     * O dublê da resposta HTTP, montado <b>antes</b> de entrar num when: o Mockito não aceita um
     * mock() criado dentro dos argumentos de outro when().
     */
    private static HttpResponse<String> resposta(int status, String corpo) {
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(status);
        when(resposta.body()).thenReturn(corpo);
        return resposta;
    }

    private void respondendo(HttpResponse<String> primeira, HttpResponse<String> depois)
            throws Exception {
        var stub = when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(primeira);
        if (depois != null) {
            stub.thenReturn(depois);
        }
    }

    @Test
    @DisplayName("o consumo chega ao medidor com a origem, o modelo e os seis números")
    void registraOConsumoInteiro() throws Exception {
        respondendo(resposta(200, USO), null);

        cliente().criarMensagem(Origem.BRIEFING, json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode());

        verify(medidor, times(1)).registrar(Origem.BRIEFING, "modelo-a", 812, 96, 4096, 2310, 3);
    }

    @Test
    @DisplayName("cada origem chega como ela mesma, nunca como conversa por omissão")
    void cadaOrigemChegaComoElaMesma() throws Exception {
        respondendo(resposta(200, USO), null);
        var cliente = cliente();

        cliente.criarMensagem(Origem.CONVERSA, json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode());
        cliente.criarMensagem(Origem.CLASSIFICADOR, json.createArrayNode(),
                json.createArrayNode(), json.createArrayNode(), 60,
                java.time.Duration.ofSeconds(5));
        cliente.criarMensagem(Origem.ANOTADOR, "um system de texto", json.createArrayNode(),
                json.createArrayNode());

        verify(medidor).registrar(eq(Origem.CONVERSA), anyString(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong());
        verify(medidor).registrar(eq(Origem.CLASSIFICADOR), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong());
        verify(medidor).registrar(eq(Origem.ANOTADOR), anyString(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong());
    }

    @Test
    @DisplayName("um 429 seguido de 200 conta uma vez, não duas")
    void tentativaRepetidaContaUmaVez() throws Exception {
        respondendo(resposta(429, "{}"), resposta(200, USO));

        cliente().criarMensagem(Origem.CONVERSA, json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode());

        verify(medidor, times(1)).registrar(any(Origem.class), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("chamada que termina em erro não entra na conta: não houve cobrança a registrar")
    void erroNaoRegistra() throws Exception {
        respondendo(resposta(400, "{}"), null);

        assertThrows(ErroDaApiDoModelo.class, () -> cliente().criarMensagem(Origem.CONVERSA,
                json.createArrayNode(), json.createArrayNode(), json.createArrayNode()));

        verify(medidor, never()).registrar(any(Origem.class), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("resposta sem bloco de usage registra zeros em vez de estourar")
    void respostaSemUsage() throws Exception {
        respondendo(resposta(200, "{\"content\":[]}"), null);

        var raiz = cliente().criarMensagem(Origem.CONVERSA, json.createArrayNode(),
                json.createArrayNode(), json.createArrayNode());

        assertEquals(0, raiz.path("content").size());
        verify(medidor).registrar(Origem.CONVERSA, "modelo-a", 0, 0, 0, 0, 0);
    }
}
