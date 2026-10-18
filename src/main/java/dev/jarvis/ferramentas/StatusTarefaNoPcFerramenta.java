package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class StatusTarefaNoPcFerramenta implements Ferramenta {
    private final TarefasComputerUse tarefas;
    private final JsonMapper json;
    public StatusTarefaNoPcFerramenta(TarefasComputerUse tarefas, JsonMapper json) {
        this.tarefas = tarefas; this.json = json;
    }
    @Override public String nome() { return "status_tarefa_no_pc"; }
    @Override public String descricao() { return "Consulta pelo ID o estado e progresso de uma tarefa visual do PC."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string");
        raiz.putArray("required").add("id"); return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessario."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        var estado = tarefas.estado(Json.texto(entrada.path("id")), contexto.usuarioAutenticado());
        return ResultadoFerramenta.texto(estado == null ? "Tarefa nao encontrada." :
                "Tarefa " + estado.id() + ": " + estado.estado() + "; ciclos " + estado.ciclos()
                + "; acoes " + estado.acoes() + ". " + estado.motivo());
    }
}
