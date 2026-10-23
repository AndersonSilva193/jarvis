package dev.jarvis.computer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import tools.jackson.databind.json.JsonMapper;

/**
 * O computer use também vira custo — e <b>só uma vez</b>.
 *
 * O {@code TarefasComputerUse} continua somando os mesmos tokens para o Retrato que a HUD mostra;
 * quem registra no diário são os provedores. Se um dia alguém acrescentar o registro lá também, é
 * este teste que acusa a conta dobrada.
 */
@SuppressWarnings("unchecked")
class ProvedoresComputerUseCustoTest {

    private static final String USO_ANTHROPIC = """
            {"content":[],"usage":{"input_tokens":1500,"output_tokens":120,
             "cache_creation_input_tokens":256,"cache_read_input_tokens":1024}}
            """;

    private static final String USO_OPENAI = """
            {"id":"resp_1","output":[],"usage":{"input_tokens":2000,"output_tokens":90}}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient http = mock(HttpClient.class);
    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);

    private JarvisProperties props(String provedor) {
        return new JarvisProperties("Jarvis", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave-anthropic", "modelo-conversa", 1024, 512),
                null, null, null, null, null, null, null, null,
                new JarvisProperties.ComputerUse(true, provedor, "modelo-visual-anthropic",
                        "modelo-visual-openai", "chave-openai", 20, 50, 60),
                null, null);
    }

    private void respondendo(String corpo) throws Exception {
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(200);
        when(resposta.body()).thenReturn(corpo);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resposta);
    }

    @Test
    @DisplayName("o provedor da Anthropic registra com o modelo visual, não com o da conversa")
    void anthropicRegistraComOModeloVisual() throws Exception {
        respondendo(USO_ANTHROPIC);
        var provedor = new ProvedorAnthropicComputerUse(http, json, props("anthropic"), medidor);

        provedor.iniciar("abrir o menu", new byte[] {1, 2, 3}, json.createArrayNode(),
                Duration.ofSeconds(60));

        verify(medidor, times(1)).registrar(Origem.COMPUTER_USE, "modelo-visual-anthropic",
                1500, 120, 256, 1024, 0);
    }

    @Test
    @DisplayName("cada resposta do provedor conta uma vez, e um ciclo a mais soma outra")
    void cadaRespostaContaUmaVez() throws Exception {
        respondendo(USO_ANTHROPIC);
        var provedor = new ProvedorAnthropicComputerUse(http, json, props("anthropic"), medidor);

        provedor.iniciar("abrir o menu", new byte[] {1}, json.createArrayNode(),
                Duration.ofSeconds(60));
        provedor.continuar(java.util.List.of(), Duration.ofSeconds(60));

        verify(medidor, times(2)).registrar(eq(Origem.COMPUTER_USE), anyString(), anyLong(),
                anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("o da OpenAI registra sem cache: a Responses API não informa esses campos")
    void openaiRegistraSemCache() throws Exception {
        respondendo(USO_OPENAI);
        var provedor = new ProvedorOpenAiComputerUse(http, json, props("openai"), medidor);

        provedor.iniciar("abrir o menu", new byte[] {1, 2, 3}, json.createArrayNode(),
                Duration.ofSeconds(60));

        verify(medidor, times(1)).registrar(Origem.COMPUTER_USE, "modelo-visual-openai",
                2000, 90, 0, 0, 0);
    }
}
