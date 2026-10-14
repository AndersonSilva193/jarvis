package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.SistemaDoJarvis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A narração do briefing, com o ClaudeClient dublado: sem rede e sem Spring. */
class NarradorDeBriefingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TEXTO = "Bom dia, Anderson!\n\nCLIMA\nCuritiba: 18 °C agora";

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
    private final NarradorDeBriefing narrador = new NarradorDeBriefing(claude, sistema, JSON);

    /** Sem isto o dublê devolveria null e a chamada iria sem system nenhum. */
    @BeforeEach
    void prepararSistema() {
        ArrayNode blocos = JSON.createArrayNode();
        blocos.addObject().put("type", "text").put("text", "personalidade");
        when(sistema.blocos(any(Canal.class))).thenReturn(blocos);
    }

    private static JsonNode resposta(String texto) {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    @Test
    @DisplayName("pede o system do canal briefing, que é o das regras de fala")
    void usaOCanalBriefing() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("narrado"));

        narrador.narrar(TEXTO);

        verify(sistema).blocos(Canal.BRIEFING);
    }

    @Test
    @DisplayName("devolve o texto narrado pelo modelo")
    void narra() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("Bom dia, senhor. Dezoito graus em Curitiba."));

        assertEquals("Bom dia, senhor. Dezoito graus em Curitiba.", narrador.narrar(TEXTO));
    }

    @Test
    @DisplayName("não manda ferramenta nenhuma: o modelo não pode chamar o briefing de dentro do briefing")
    void semFerramentas() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("narrado"));

        narrador.narrar(TEXTO);

        ArgumentCaptor<ArrayNode> ferramentas = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(ArrayNode.class), any(ArrayNode.class), ferramentas.capture());
        assertTrue(ferramentas.getValue().isEmpty(), "a narração não pode levar ferramentas");
    }

    @Test
    @DisplayName("o briefing vai inteiro na mensagem, com a ordem de não alterar dado")
    void mandaOBriefingEAsRegras() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("narrado"));

        narrador.narrar(TEXTO);

        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(ArrayNode.class), mensagens.capture(), any(ArrayNode.class));
        String conteudo = mensagens.getValue().get(0).path("content").stringValue();

        assertTrue(conteudo.contains(TEXTO), conteudo);
        assertTrue(conteudo.contains("não altere nenhum número"), conteudo);
    }

    @Test
    @DisplayName("falha na API devolve vazio, para quem pediu ficar com o texto montado em Java")
    void falhaNaoEstoura() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenThrow(new IOException("API do Claude respondeu HTTP 529"));

        assertEquals("", narrador.narrar(TEXTO));
    }
}
