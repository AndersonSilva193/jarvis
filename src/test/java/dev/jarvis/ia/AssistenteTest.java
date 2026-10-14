package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que o Assistente manda para a API, com o ClaudeClient dublado: sem rede e sem Spring.
 *
 * O foco é o "preserved thinking" — os blocos de pensamento vêm assinados e presos ao prefixo exato
 * (system + ferramentas + mensagens) que os produziu. Replicar um deles sob outro prefixo é HTTP 400,
 * e foi assim que o bot quebrou em produção.
 */
class AssistenteTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final Assistente assistente = new Assistente(
            claude,
            new Ferramentas(List.of(), JSON),
            new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props()),
            JSON);

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null);
    }

    /** Resposta com um bloco de pensamento assinado antes do texto, como o modelo devolve. */
    private static JsonNode respostaComPensamento(String texto) {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "end_turn");
        ArrayNode conteudo = resposta.putArray("content");
        ObjectNode pensamento = conteudo.addObject();
        pensamento.put("type", "thinking");
        pensamento.put("thinking", "deixa eu pensar...");
        pensamento.put("signature", "assinatura-presa-ao-prefixo");
        conteudo.addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    /** Resposta que pede uma ferramenta, também precedida de pensamento. */
    private static JsonNode respostaComFerramenta() {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "tool_use");
        ArrayNode conteudo = resposta.putArray("content");
        ObjectNode pensamento = conteudo.addObject();
        pensamento.put("type", "thinking");
        pensamento.put("thinking", "preciso de uma ferramenta");
        pensamento.put("signature", "assinatura");
        ObjectNode uso = conteudo.addObject();
        uso.put("type", "tool_use");
        uso.put("id", "tu_1");
        uso.put("name", "inexistente");
        uso.set("input", JSON.createObjectNode());
        return resposta;
    }

    private List<ArrayNode> mensagensEnviadas() throws Exception {
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce())
                .criarMensagem(any(ArrayNode.class), captor.capture(), any(ArrayNode.class));
        return captor.getAllValues();
    }

    private static List<JsonNode> blocosDe(ArrayNode mensagens, String tipo) {
        List<JsonNode> achados = new ArrayList<>();
        for (JsonNode mensagem : mensagens) {
            JsonNode conteudo = mensagem.path("content");
            for (int i = 0; conteudo.isArray() && i < conteudo.size(); i++) {
                if (tipo.equals(conteudo.get(i).path("type").stringValue())) {
                    achados.add(conteudo.get(i));
                }
            }
        }
        return achados;
    }

    @Test
    @DisplayName("o histórico replicado não leva blocos de pensamento do turno anterior")
    void historicoSemPensamento() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComPensamento("pronto, senhor"));

        assistente.responder(1L, "oi");
        assistente.responder(1L, "e agora?");

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(2, enviadas.size());

        ArrayNode segundoPedido = enviadas.get(1);
        assertTrue(segundoPedido.size() >= 3, "o segundo pedido tem que carregar o turno anterior");
        assertTrue(blocosDe(segundoPedido, "thinking").isEmpty(),
                "pensamento do turno anterior iria assinado sob outro prefixo: HTTP 400");
        assertFalse(blocosDe(segundoPedido, "text").isEmpty(),
                "o texto da resposta anterior tem que continuar no histórico");
    }

    @Test
    @DisplayName("dentro do mesmo turno o pensamento é devolvido intacto, como o laço de ferramentas exige")
    void pensamentoPreservadoDentroDoTurno() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComFerramenta())
                .thenReturn(respostaComPensamento("feito"));

        assistente.responder(1L, "verifica o site");

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(2, enviadas.size(), "uma chamada para pedir a ferramenta e outra com o resultado");
        assertFalse(blocosDe(enviadas.get(1), "thinking").isEmpty(),
                "o pensamento que acompanha o tool_use tem que voltar no mesmo turno");
    }

    @Test
    @DisplayName("o system é montado uma vez por pedido e repetido em todas as idas e vindas")
    void systemEstavelDuranteOTurno() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComFerramenta())
                .thenReturn(respostaComPensamento("feito"));

        assistente.responder(1L, "verifica o site");

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2))
                .criarMensagem(captor.capture(), any(ArrayNode.class), any(ArrayNode.class));

        // Identidade, não igualdade: no mesmo minuto o texto sairia igual mesmo remontado, e o
        // teste passaria com o bug. O que precisa valer é que foi montado uma vez só.
        assertSame(captor.getAllValues().get(0), captor.getAllValues().get(1),
                "remontar o system no meio do turno invalida o pensamento já devolvido");
    }
}
