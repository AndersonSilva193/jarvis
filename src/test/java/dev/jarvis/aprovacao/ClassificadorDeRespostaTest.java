package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.aprovacao.ClassificadorDeResposta.Decisao;
import dev.jarvis.ia.ClaudeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/** Entender "pode" e "agora não" sem gastar nada, e nunca executar quando não dá para saber. */
class ClassificadorDeRespostaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PERGUNTA = "Posso substituir o original de nota.md?";

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final ClassificadorDeResposta classificador = new ClassificadorDeResposta(claude, JSON);

    @Test
    @DisplayName("as listas decidem sozinhas, com acento e pontuação")
    void listasDecidemSemModelo() throws Exception {
        for (String sim : new String[] { "sim", "Sim!", "  PODE  ", "pode sim", "Pode ir.",
                "manda ver", "bora", "ok", "Beleza!", "blz", "claro", "autorizo", "Isso." })
            assertEquals(Decisao.SIM, classificador.classificar(PERGUNTA, sim), sim);

        for (String nao : new String[] { "não", "Não.", "n", "agora não", "Deixa pra lá",
                "deixa quieto", "espera", "cancela", "nem pensar", "Melhor não!", "negativo" })
            assertEquals(Decisao.NAO, classificador.classificar(PERGUNTA, nao), nao);

        verify(claude, never()).criarMensagem(any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class), anyInt(), any(Duration.class));
    }

    @Test
    @DisplayName("frase com ressalva não bate na lista e vai ao modelo, curta e sem ferramentas")
    void frasesLongasVaoAoModelo() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                anyInt(), any(Duration.class))).thenReturn(resposta("{\"decisao\":\"ajuste\"}"));

        assertEquals(Decisao.AJUSTE,
                classificador.classificar(PERGUNTA, "pode, mas só a pasta src"));

        ArgumentCaptor<ArrayNode> ferramentas = ArgumentCaptor.forClass(ArrayNode.class);
        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(ArrayNode.class), mensagens.capture(),
                ferramentas.capture(), eq(60), eq(Duration.ofSeconds(5)));
        assertTrue(ferramentas.getValue().isEmpty(), "um classificador não chama ferramenta");
        String enviado = mensagens.getValue().toString();
        assertTrue(enviado.contains("pode, mas só a pasta src"), enviado);
        assertTrue(enviado.contains("nota.md"), "a pergunta do Jarvis vai junto: " + enviado);
    }

    @Test
    @DisplayName("as outras saídas do modelo chegam inteiras")
    void demaisDecisoesDoModelo() throws Exception {
        assertEquals(Decisao.OUTRO_ASSUNTO, comModelo("{\"decisao\":\"outro_assunto\"}"));
        assertEquals(Decisao.SIM, comModelo("Claro: {\"decisao\": \"SIM\"}"));
        assertEquals(Decisao.NAO, comModelo("{\"decisao\":\"nao\"}"));
        assertEquals(Decisao.DUVIDA, comModelo("{\"decisao\":\"duvida\"}"));
    }

    @Test
    @DisplayName("resposta torta, valor desconhecido ou falha de rede ficam em dúvida")
    void naDuvidaNuncaExecuta() throws Exception {
        assertEquals(Decisao.DUVIDA, comModelo("sei lá, acho que ele quis dizer sim"));
        assertEquals(Decisao.DUVIDA, comModelo("{\"decisao\":\"talvez\"}"));
        assertEquals(Decisao.DUVIDA, comModelo("{\"decisao\":"));

        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                anyInt(), any(Duration.class))).thenThrow(new IOException("timeout"));
        assertEquals(Decisao.DUVIDA, classificador.classificar(PERGUNTA, "faz aí se der tempo"));
    }

    @Test
    @DisplayName("sem modelo configurado, o que não está na lista fica em dúvida")
    void semModeloSoValeALista() {
        var soLista = ClassificadorDeResposta.semModelo();
        assertEquals(Decisao.SIM, soLista.classificar(PERGUNTA, "pode"));
        assertEquals(Decisao.NAO, soLista.classificar(PERGUNTA, "melhor não"));
        assertEquals(Decisao.DUVIDA, soLista.classificar(PERGUNTA, "pode, mas só a pasta src"));
        assertEquals(Decisao.DUVIDA, soLista.classificar(PERGUNTA, "   "));
    }

    private Decisao comModelo(String texto) throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                anyInt(), any(Duration.class))).thenReturn(resposta(texto));
        return classificador.classificar(PERGUNTA, "faz aí se der tempo");
    }

    private static JsonNode resposta(String texto) {
        var raiz = JSON.createObjectNode();
        raiz.putArray("content").addObject().put("type", "text").put("text", texto);
        return raiz;
    }
}
