package dev.jarvis.computer;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProtocolosComputerUseTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void anthropicUsaNomeEIdDoToolset() throws Exception {
        var resposta = json.readTree("""
                {"content":[{"type":"text","text":"vou olhar"},
                  {"type":"tool_use","id":"toolu_1","toolset_name":"computer",
                   "name":"left_click","input":{"coordinate":[10,20]}}],
                 "usage":{"input_tokens":12,"output_tokens":8}}
                """);
        var passo = ProvedorAnthropicComputerUse.interpretarResposta(resposta);
        assertEquals("toolu_1", passo.chamadas().getFirst().id());
        assertEquals("left_click", passo.chamadas().getFirst().nome());
        assertEquals(12, passo.tokensEntrada());
        assertThrows(IllegalStateException.class, () ->
                ProvedorAnthropicComputerUse.interpretarResposta(json.readTree("""
                        {"content":[{"type":"tool_use","id":"x","name":"key",
                          "toolset_name":"browser","input":{}}]}
                        """)));
    }

    @Test void openAiPreservaChamadaEAcoesEmOrdemERecusaSafetyCheck() throws Exception {
        var resposta = json.readTree("""
                {"output":[{"type":"computer_call","call_id":"call_1","actions":[
                  {"type":"click","x":10,"y":20},{"type":"type","text":"teste"}]}],
                 "usage":{"input_tokens":20,"output_tokens":4}}
                """);
        var passo = ProvedorOpenAiComputerUse.interpretarResposta(resposta);
        assertEquals(2, passo.chamadas().size());
        assertEquals("call_1", passo.chamadas().get(1).id());
        assertEquals("click", passo.chamadas().getFirst().nome());
        assertEquals("type", passo.chamadas().get(1).nome());
        assertThrows(IllegalStateException.class, () ->
                ProvedorOpenAiComputerUse.interpretarResposta(json.readTree("""
                        {"output":[{"type":"computer_call","call_id":"call_2",
                          "pending_safety_checks":[{"id":"check"}],"actions":[]}]}
                        """)));
    }

    @Test void recusaAcoesForaDoContrato() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> TarefasComputerUse.validar(
                new ProvedorComputerUse.Chamada("x", "bash", json.readTree("{}"))));
        assertThrows(IllegalArgumentException.class, () -> TarefasComputerUse.validar(
                new ProvedorComputerUse.Chamada("x", "wait", json.readTree("{\"duration\":99}"))));
        assertThrows(IllegalArgumentException.class, () -> TarefasComputerUse.validar(
                new ProvedorComputerUse.Chamada("x", "type", json.readTree("{\"text\":42}"))));
        assertThrows(IllegalArgumentException.class, () -> TarefasComputerUse.validar(
                new ProvedorComputerUse.Chamada("x", "click", json.readTree("{\"x\":-1,\"y\":2}"))));
    }
}
