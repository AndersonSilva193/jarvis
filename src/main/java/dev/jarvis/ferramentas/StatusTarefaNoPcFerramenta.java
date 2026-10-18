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
    @Override public String descricao() { return "Consulta o estado da ultima tarefa visual do usuario no PC; ID opcional para tarefa especifica."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessario."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        String id = Json.texto(entrada.path("id"));
        var estado = id.isBlank() ? tarefas.ultima(contexto.usuarioAutenticado())
                : tarefas.estado(id, contexto.usuarioAutenticado());
        return ResultadoFerramenta.texto(estado == null ? "Tarefa nao encontrada." :
                "Tarefa \"" + estado.nome() + "\": " + estado.estado() + "; ciclos " + estado.ciclos()
                + "; acoes " + estado.acoes() + ". " + estado.motivo());
    }
}
