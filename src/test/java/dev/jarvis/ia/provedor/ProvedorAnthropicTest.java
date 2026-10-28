package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.custo.Origem;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.PerfilDeEsforco;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Qual sobrecarga do {@link ClaudeClient} cada tipo de chamada alcança. É o que mantém de pé o cache
 * da conversa, o esforço por perfil e a chamada curta de tentativa única, que moram lá.
 */
class ProvedorAnthropicTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final ProvedorAnthropic provedor = new ProvedorAnthropic(claude, JSON);

    private static JsonNode respostaFinal() {
        ObjectNode raiz = JSON.createObjectNode().put("stop_reason", "end_turn");
        raiz.putArray("content").addObject().put("type", "text").put("text", "Pronto.");
        return raiz;
    }

    private static PedidoAoModelo pedido(OpcoesDoPedido opcoes) {
        return new PedidoAoModelo(InstrucoesDeSistema.de(new BlocoDeSistema("sistema", null)),
                List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "oi")), ListaDeFerramentas.vazia(), opcoes);
    }

    @Test
    @DisplayName("com esforço informado, a sobrecarga de perfil explícito — a do laço de ferramentas")
    void comEsforco() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class))).thenReturn(respostaFinal());

        RespostaDoModelo resposta = provedor.responder(
                pedido(OpcoesDoPedido.de(Origem.CONVERSA, PerfilDeEsforco.TAREFA)));

        assertEquals(List.of("Pronto."), resposta.textos());
        verify(claude).criarMensagem(eq(Origem.CONVERSA), eq(PerfilDeEsforco.TAREFA),
                any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
    }

    @Test
    @DisplayName("só com a origem, a sobrecarga que deriva o perfil dela")
    void soComOrigem() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenReturn(respostaFinal());

        provedor.responder(pedido(OpcoesDoPedido.de(Origem.BRIEFING)));

        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(eq(Origem.BRIEFING), any(ArrayNode.class), mensagens.capture(),
                any(ArrayNode.class));
        assertEquals("[{\"role\":\"user\",\"content\":\"oi\"}]", mensagens.getValue().toString());
    }

    @Test
    @DisplayName("a chamada curta leva teto e prazo, e é a de tentativa única")
    void chamadaCurta() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class), anyInt(), any(Duration.class))).thenReturn(respostaFinal());

        provedor.responder(pedido(OpcoesDoPedido.curta(Origem.CLASSIFICADOR, 60, Duration.ofSeconds(5))));

        verify(claude).criarMensagem(eq(Origem.CLASSIFICADOR), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class), eq(60), eq(Duration.ofSeconds(5)));
    }

    @Test
    @DisplayName("o mesmo system e as mesmas ferramentas em todos os passos do pedido")
    void mesmoPrefixoEntrePassos() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class))).thenReturn(respostaFinal());
        PedidoAoModelo primeiro = pedido(OpcoesDoPedido.de(Origem.CONVERSA, PerfilDeEsforco.CONVERSA));
        PedidoAoModelo segundo = new PedidoAoModelo(primeiro.sistema(), List.of(
                Mensagem.texto(Mensagem.Papel.USUARIO, "oi"),
                Mensagem.texto(Mensagem.Papel.ASSISTENTE, "Pois não.")),
                primeiro.ferramentas(), primeiro.opcoes());

        provedor.responder(primeiro);
        provedor.responder(segundo);

        ArgumentCaptor<ArrayNode> system = ArgumentCaptor.forClass(ArrayNode.class);
        ArgumentCaptor<ArrayNode> ferramentas = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                system.capture(), any(ArrayNode.class), ferramentas.capture());
        assertSame(system.getAllValues().get(0), system.getAllValues().get(1));
        assertSame(ferramentas.getAllValues().get(0), ferramentas.getAllValues().get(1));
    }

    @Test
    @DisplayName("o erro do cliente atravessa como está")
    void erroAtravessa() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenThrow(new IOException("HTTP 529"));

        assertThrows(IOException.class, () -> provedor.responder(pedido(OpcoesDoPedido.de(Origem.BRIEFING))));
    }

    @Test
    @DisplayName("opções que o cliente não sabe atender são recusadas na hora")
    void opcoesImpossiveis() {
        assertThrows(IllegalArgumentException.class,
                () -> new OpcoesDoPedido(Origem.ANOTADOR, null, 0, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class,
                () -> new OpcoesDoPedido(Origem.ANOTADOR, PerfilDeEsforco.APOIO, 60, Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> OpcoesDoPedido.de(null));
    }
}
