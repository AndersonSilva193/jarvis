package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class CompactadorBuscaFerramentasTest {
    @Test void buscaDeFerramentasNaoViraBuscaNaWebNaNota() {
        JsonMapper json = JsonMapper.builder().build();
        ObjectNode mensagem = json.createObjectNode().put("role", "assistant");
        ArrayNode blocos = mensagem.putArray("content");
        blocos.addObject().put("type", "server_tool_use").put("name", "tool_search_tool_bm25")
                .put("id", "srvtoolu-busca").putObject("input")
                .put("query", "ler documento pdf");
        blocos.addObject().put("type", "tool_search_tool_result")
                .put("tool_use_id", "srvtoolu-busca").putObject("content")
                .put("type", "tool_search_tool_search_result").putArray("tool_references")
                .addObject().put("type", "tool_reference").put("tool_name", "ler_arquivo");
        assertEquals("", CompactadorDeHistorico.padrao(json).nota(List.of(mensagem)));
    }
}
