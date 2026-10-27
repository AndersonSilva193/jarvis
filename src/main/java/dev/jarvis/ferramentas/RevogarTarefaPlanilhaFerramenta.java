package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.TarefasPlanilhaService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Revoga uma autorização recorrente do próprio usuário. */
@Component
public class RevogarTarefaPlanilhaFerramenta implements Ferramenta {
    private final TarefasPlanilhaService tarefas;
    private final JsonMapper json;
    public RevogarTarefaPlanilhaFerramenta(TarefasPlanilhaService tarefas, JsonMapper json) {
        this.tarefas = tarefas; this.json = json;
    }
    @Override public String nome() { return "revogar_tarefa_planilha"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PLANILHAS; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() { return "Revoga pelo ID uma tarefa de planilha do usuário autenticado."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("idTarefa").put("type", "string");
        raiz.putArray("required").add("idTarefa");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto)
            throws Exception {
        String id = Json.texto(entrada.path("idTarefa"));
        if (contexto.usuarioAutenticado() <= 0 || !id.matches("[0-9a-fA-F-]{36}")
                || !contexto.pedidoOriginal().toLowerCase().matches("(?s).*(revog|cancel|parar).*(tarefa|planilha).*"))
            return ResultadoFerramenta.texto("Revogação exige pedido explícito e ID da tarefa.");
        return ResultadoFerramenta.texto(tarefas.revogar(id, contexto.usuarioAutenticado())
                ? "Tarefa revogada." : "Tarefa não encontrada para este usuário.");
    }
}
