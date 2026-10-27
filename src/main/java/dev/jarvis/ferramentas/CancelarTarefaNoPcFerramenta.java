package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CancelarTarefaNoPcFerramenta implements Ferramenta {
    private final TarefasComputerUse tarefas;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;
    public CancelarTarefaNoPcFerramenta(TarefasComputerUse tarefas, Aprovacoes aprovacoes,
            JsonMapper json) {
        this.tarefas = tarefas; this.aprovacoes = aprovacoes; this.json = json;
    }
    @Override public String nome() { return "cancelar_tarefa_no_pc"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PC; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Cancela uma confirmacao pendente de tarefa visual ou pede parada da ultima tarefa "
                + "iniciada no PC. Sem ID, prioriza a confirmacao pendente; ID opcional para tarefa "
                + "iniciada especifica. Use se o usuario pedir para cancelar a tarefa pendente antes "
                + "de iniciar outra. Uma confirmacao pendente nao possui ID de tarefa.";
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessario."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        String id = Json.texto(entrada.path("id"));
        if (id.isBlank()) {
            var cancelamento = aprovacoes.cancelarTarefaPendente(
                    contexto.usuarioAutenticado(), contexto.idPedido());
            if (cancelamento == Aprovacoes.Cancelamento.CANCELADA)
                return ResultadoFerramenta.texto("Confirmação pendente da tarefa cancelada. Pode pedir outra tarefa.");
            if (cancelamento == Aprovacoes.Cancelamento.RECUSADA)
                return ResultadoFerramenta.texto("Confirmação pendente só pode ser cancelada em nova mensagem autenticada.");
            var ultima = tarefas.ultima(contexto.usuarioAutenticado());
            id = ultima == null ? "" : ultima.id();
        }
        return ResultadoFerramenta.texto(tarefas.cancelar(id,
                contexto.usuarioAutenticado()) ? "Cancelamento solicitado." : "Tarefa nao encontrada ou encerrada.");
    }
}
