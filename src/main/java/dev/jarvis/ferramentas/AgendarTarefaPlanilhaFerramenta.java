package dev.jarvis.ferramentas;

import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.pc.TarefasPlanilhaService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Registra uma autorização recorrente somente após confirmação humana. */
@Component
public class AgendarTarefaPlanilhaFerramenta implements Ferramenta {
    private final TarefasPlanilhaService tarefas;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;
    public AgendarTarefaPlanilhaFerramenta(TarefasPlanilhaService tarefas,
            Aprovacoes aprovacoes, JsonMapper json) {
        this.tarefas = tarefas; this.aprovacoes = aprovacoes; this.json = json;
    }
    @Override public String nome() { return "agendar_tarefa_planilha"; }
    @Override public String descricao() { return "Agenda edição recorrente de um XLSX inteiro por ID "
            + "de listar_arquivos. Exige pedido com nome exato do arquivo e confirmação autenticada "
            + "em nova mensagem. A tarefa localiza células sozinha; busca ambígua interrompe a execução. "
            + "substituirFormula só quando solicitado explicitamente. Cron Spring de 6 campos no fuso configurado."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode p = raiz.putObject("properties");
        p.putObject("idArquivo").put("type", "string");
        p.putObject("nomeArquivo").put("type", "string");
        p.putObject("instrucao").put("type", "string");
        p.putObject("cron").put("type", "string");
        p.putObject("substituirFormula").put("type", "boolean");
        raiz.putArray("required").add("idArquivo").add("nomeArquivo").add("instrucao").add("cron");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        String id = Json.texto(entrada.path("idArquivo"));
        String nome = Json.texto(entrada.path("nomeArquivo"));
        String instrucao = Json.texto(entrada.path("instrucao"));
        String cron = Json.texto(entrada.path("cron"));
        boolean formula = entrada.path("substituirFormula").asBoolean(false);
        if (contexto.usuarioAutenticado() <= 0 || contexto.idPedido() == null
                || !id.matches("[0-9a-fA-F-]{36}") || !nome.toLowerCase().endsWith(".xlsx")
                || !EditarArquivoFerramenta.arquivoConstaDoPedido(contexto.pedidoOriginal(),
                        nome, id, contexto.ultimoAnexoDeArquivo()))
            return ResultadoFerramenta.texto("Indique o nome exato do XLSX e leia-o antes de agendar.");
        if (formula && !contexto.pedidoOriginal().toLowerCase().contains("fórmula"))
            return ResultadoFerramenta.texto("Substituir fórmulas exige pedido explícito.");
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron);
            long usuario = contexto.usuarioAutenticado();
            String descricao = "Autorizar edições recorrentes em todo " + nome + " (" + cron + ")"
                    + ". Instrução: " + instrucao
                    + (formula ? ", inclusive substituição de fórmulas" : "");
            var confirmacao = aprovacoes.pedir("tarefa_planilha", descricao,
                    "Posso agendar edições recorrentes em " + nome + " (" + cron + ")?",
                    Aprovacoes.Risco.COMUM, usuario, contexto.idPedido(), () -> {
                        try {
                            var tarefa = tarefas.registrar(usuario, id, nome, instrucao, cron, formula);
                            return "Tarefa de planilha agendada: " + tarefa.id();
                        } catch (IOException e) { return "Não agendei a tarefa: " + e.getMessage(); }
                    });
            return new ResultadoFerramenta(descricao + ". Confirme em NOVA mensagem: confirmo.",
                    List.of(), List.of(), confirmacao, List.of(), List.of());
        } catch (RuntimeException e) {
            return ResultadoFerramenta.texto("Agenda inválida: " + e.getMessage());
        }
    }
}
