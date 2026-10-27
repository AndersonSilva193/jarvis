package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.TarefasPlanilhaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Consulta autorizações recorrentes do próprio usuário. */
@Component
public class ListarTarefasPlanilhaFerramenta implements Ferramenta {
    private final TarefasPlanilhaService tarefas;
    private final JsonMapper json;
    public ListarTarefasPlanilhaFerramenta(TarefasPlanilhaService tarefas, JsonMapper json) {
        this.tarefas = tarefas; this.json = json;
    }
    @Override public String nome() { return "listar_tarefas_planilha"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PLANILHAS; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() { return "Lista as tarefas de planilha autorizadas pelo usuário, "
            + "com ID, arquivo, agenda e próxima execução para permitir revogação."; }
    @Override public ObjectNode esquemaEntrada() { return json.createObjectNode().put("type", "object"); }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (contexto.usuarioAutenticado() <= 0) return ResultadoFerramenta.texto("Pedido autenticado necessário.");
        var lista = tarefas.listar(contexto.usuarioAutenticado());
        if (lista.isEmpty()) return ResultadoFerramenta.texto("Nenhuma tarefa de planilha cadastrada.");
        StringBuilder saida = new StringBuilder();
        for (var t : lista) saida.append(t.id()).append(" — ").append(t.nomeArquivo())
                .append(" — ").append(t.ativa() ? "ativa" : "revogada")
                .append(" — ").append(t.cron()).append(" — próxima ").append(t.proxima())
                .append('\n');
        return ResultadoFerramenta.texto(saida.toString().trim());
    }
}
