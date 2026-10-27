package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.Ferramentas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A parada no meio do pedido, com o ClaudeClient dublado: sem rede e sem Spring.
 *
 * <p>O que se prova aqui é o que a borda de entrada não consegue provar sozinha: a marca corta o
 * turno no passo seguinte, nenhuma ferramenta roda depois dela, e o turno cortado não fica no
 * histórico pela metade.
 */
class ParadaDoPedidoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long USUARIO = 42;

    private final ClaudeClient claude = mock(ClaudeClient.class);

    @Test
    @DisplayName("a marca encerra o turno no passo seguinte, com 'Parei, senhor.'")
    void paradaNoMeioDoLoopEncerraOTurno() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Assistente[] alvo = new Assistente[1];
        Ferramenta clima = ferramenta("clima", "Sol, 28 graus.", execucoes,
                () -> alvo[0].interromperPedidoAtual(USUARIO));
        alvo[0] = comFerramentas(clima);
        // Um thenReturn só se repete para sempre: sem a parada, este turno iria até o teto de passos.
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("clima", JSON.createObjectNode().put("cidade", "Recife")));

        var resposta = alvo[0].responder(1L, "e o tempo?", Canal.TELEGRAM, ouvinte("telegram-1"));

        assertEquals("Parei, senhor.", resposta.texto());
        assertEquals(1, execucoes.get(), "a ferramenta do passo em curso roda; nenhuma depois dela");
        verify(claude, times(1)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
    }

    @Test
    @DisplayName("ferramenta do mesmo passo não roda depois da parada")
    void segundaFerramentaDoMesmoPassoNaoRoda() throws Exception {
        AtomicInteger primeira = new AtomicInteger();
        AtomicInteger segunda = new AtomicInteger();
        Assistente[] alvo = new Assistente[1];
        Ferramenta clima = ferramenta("clima", "Sol, 28 graus.", primeira,
                () -> alvo[0].interromperPedidoAtual(USUARIO));
        Ferramenta sites = ferramenta("verificar_site", "No ar.", segunda, () -> { });
        alvo[0] = comFerramentas(clima, sites);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(duasChamadas());

        var resposta = alvo[0].responder(1L, "e o tempo? e os sites?", Canal.TELEGRAM,
                ouvinte("telegram-1"));

        assertEquals("Parei, senhor.", resposta.texto());
        assertEquals(1, primeira.get());
        assertEquals(0, segunda.get(), "a segunda ferramenta do passo já não roda");
    }

    @Test
    @DisplayName("turno interrompido não vai para o histórico pela metade")
    void turnoInterrompidoNaoEntraNoHistorico() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Assistente[] alvo = new Assistente[1];
        Ferramenta clima = ferramenta("clima", "Sol, 28 graus.", execucoes,
                () -> alvo[0].interromperPedidoAtual(USUARIO));
        alvo[0] = comFerramentas(clima);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("clima", JSON.createObjectNode().put("cidade", "Recife")))
                .thenReturn(texto("Bom dia, senhor."));

        alvo[0].responder(1L, "e o tempo em Recife?", Canal.TELEGRAM, ouvinte("telegram-1"));
        var segunda = alvo[0].responder(1L, "bom dia", Canal.TELEGRAM, ouvinte("telegram-2"));

        assertEquals("Bom dia, senhor.", segunda.texto());
        var mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                mensagens.capture(), any(ArrayNode.class));
        String enviado = mensagens.getValue().toString();
        assertFalse(enviado.contains("e o tempo em Recife"), "o pedido cortado saiu do histórico");
        assertFalse(enviado.contains("Sol, 28 graus"), "e o resultado da ferramenta também");
        assertTrue(enviado.contains("bom dia"));
    }

    @Test
    @DisplayName("sem pedido em andamento não há nada a interromper")
    void paradaSemPedidoEmAndamentoNaoFazNada() {
        Assistente parado = comFerramentas();
        assertFalse(parado.ocupado());
        assertFalse(parado.interromperPedidoAtual(USUARIO),
                "é esse false que faz a borda tratar 'para' como mensagem comum");
    }

    @Test
    @DisplayName("a parada é do dono do pedido: outro usuário autenticado não corta o turno")
    void outroUsuarioNaoInterrompe() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Assistente[] alvo = new Assistente[1];
        Ferramenta clima = ferramenta("clima", "Sol, 28 graus.", execucoes, () -> {
            assertTrue(alvo[0].ocupado());
            assertFalse(alvo[0].interromperPedidoAtual(99));
        });
        alvo[0] = comFerramentas(clima);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("clima", JSON.createObjectNode().put("cidade", "Recife")))
                .thenReturn(texto("Sol em Recife, senhor."));

        var resposta = alvo[0].responder(1L, "e o tempo?", Canal.TELEGRAM, ouvinte("telegram-1"));

        assertEquals("Sol em Recife, senhor.", resposta.texto());
    }

    private Assistente comFerramentas(Ferramenta... lista) {
        return new Assistente(claude, new Ferramentas(List.of(lista), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        JSON, props()),
                JSON);
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }

    private static OuvinteProgresso ouvinte(String id) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return id; }
            @Override public long usuarioAutenticado() { return USUARIO; }
        };
    }

    /** A ferramenta de mentira que, ao rodar, faz o que o usuário faria: pede para parar. */
    private static Ferramenta ferramenta(String nome, String retorno, AtomicInteger execucoes,
            Runnable aoExecutar) {
        return new Ferramenta() {
            public String nome() { return nome; }
            public String descricao() { return nome; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) {
                execucoes.incrementAndGet();
                aoExecutar.run();
                return retorno;
            }
        };
    }

    private static JsonNode chamada(String nome, ObjectNode entrada) {
        var resposta = JSON.createObjectNode().put("stop_reason", "tool_use");
        resposta.putArray("content").addObject().put("type", "tool_use").put("id", "tu_1")
                .put("name", nome).set("input", entrada);
        return resposta;
    }

    /** Um passo pedindo duas ferramentas, como o modelo faz quando o pedido tem duas perguntas. */
    private static JsonNode duasChamadas() {
        var resposta = JSON.createObjectNode().put("stop_reason", "tool_use");
        ArrayNode conteudo = resposta.putArray("content");
        conteudo.addObject().put("type", "tool_use").put("id", "tu_1").put("name", "clima")
                .set("input", JSON.createObjectNode());
        conteudo.addObject().put("type", "tool_use").put("id", "tu_2").put("name", "verificar_site")
                .set("input", JSON.createObjectNode());
        return resposta;
    }

    private static JsonNode texto(String texto) {
        var resposta = JSON.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }
}
