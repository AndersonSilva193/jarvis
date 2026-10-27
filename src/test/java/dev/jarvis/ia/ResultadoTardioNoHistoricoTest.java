package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.aprovacao.ClassificadorDeResposta;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.HistoricoEmArquivo;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.conversa.ResultadosTardios;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O resultado da tarefa no PC chega ao modelo na pergunta seguinte. Era o outro lado do bug do
 * corte em 300 caracteres: mesmo inteiro, o aviso ia direto ao Telegram e o histórico não sabia
 * dele, então "qual o telefone da segunda?" não tinha de onde sair.
 */
class ResultadoTardioNoHistoricoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final ResultadosTardios tardios = new ResultadosTardios();
    private final Assistente assistente = new Assistente(claude, new Ferramentas(List.of(), JSON),
            new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props()),
            JSON, new RegistroConversas(evento -> { }), new AnexosService(), BuscaWebServidor.desligada(),
            new Aprovacoes(), HistoricoEmArquivo.desligado(), ClassificadorDeResposta.semModelo(), null,
            new EsquecimentosDeMemoria(), CompactadorDeHistorico.padrao(JSON), tardios);

    @Test void resultadoDaTarefaEntraAntesDoPedidoSeguinte() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("O telefone é (41) 99999-0000."));
        String aviso = "Tarefa \"no Chrome abrir o Google Maps\": concluido. 2. Academia X\nTel: (41) 99999-0000";
        tardios.registrar(42, "a tarefa no PC \"no Chrome abrir o Google Maps\"", aviso);

        assistente.responder(42, "qual o telefone da segunda?");

        ArrayNode enviadas = mensagensEnviadas().getFirst();
        assertEquals(3, enviadas.size(), "nota, aviso e o pedido novo: " + enviadas);
        assertEquals("user", enviadas.get(0).path("role").stringValue());
        assertTrue(enviadas.get(0).toString().contains("nota do servidor"), enviadas.toString());
        assertEquals("assistant", enviadas.get(1).path("role").stringValue());
        assertTrue(enviadas.get(1).toString().contains("(41) 99999-0000"), "o aviso vai inteiro");
        assertTrue(enviadas.get(2).toString().contains("qual o telefone da segunda?"));
    }

    @Test void filaEsvaziaDepoisDeDrenar() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("Certo."));
        tardios.registrar(42, "a tarefa no PC \"x\"", "resultado");
        assistente.responder(42, "oi");
        assertTrue(tardios.drenar(42).isEmpty(), "o item entra no histórico uma vez só");
    }

    private List<ArrayNode> mensagensEnviadas() throws Exception {
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), captor.capture(), any(ArrayNode.class));
        return captor.getAllValues();
    }

    private static JsonNode resposta(String texto) {
        ObjectNode resposta = JSON.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }
}
