package dev.jarvis.ferramentas;

import java.util.List;
import org.springframework.stereotype.Component;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.IntencoesPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pede autorizacao humana para uma tarefa visual delimitada no PC. */
@Component
public class TarefaNoPcFerramenta implements Ferramenta {
    private final TarefasComputerUse tarefas;
    private final IntencoesPc intencoes;
    private final JsonMapper json;
    public TarefaNoPcFerramenta(TarefasComputerUse tarefas, IntencoesPc intencoes, JsonMapper json) {
        this.tarefas = tarefas; this.intencoes = intencoes; this.json = json;
    }
    @Override public String nome() { return "tarefa_no_pc"; }
    @Override public String descricao() {
        if (!tarefas.disponivel()) return "Computer use indisponivel agora: desativado ou agente "
                + "do PC offline. Nao prometa executar tarefa visual enquanto estiver indisponivel.";
        return "Ultimo recurso para executar tarefa pela interface visual do monitor principal do PC. "
                + "Se o usuario pediu uma acao que pode ser feita no PC e resposta direta, busca, "
                + "criar_arquivo, rotina cadastrada ou acao fixa nao resolverem, use esta ferramenta "
                + "para pedir confirmacao. Nao diga que falta ferramenta antes de tentar este caminho. "
                + "Descreva objetivo e mudancas permitidas em escopo curto. "
                + "A tarefa so inicia apos nova mensagem autenticada do usuario. "
                + "Novo pedido autenticado substitui uma confirmacao anterior ainda pendente; "
                + "se o usuario pedir cancelamento explicito, use cancelar_tarefa_no_pc sem ID antes. "
                + "Nunca use para pagamentos, compras, senhas ou shell.";
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("objetivo").put("type", "string")
                .put("description", "Objetivo concreto e mudancas autorizadas, sem segredo.");
        raiz.putArray("required").add("objetivo");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessario."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!tarefas.disponivel()) return ResultadoFerramenta.texto(
                "Computer use desativado ou agente do PC offline.");
        String objetivo = Json.texto(entrada.path("objetivo")).trim();
        String original = contexto.pedidoOriginal();
        if (objetivo.isBlank() || objetivo.length() > 500 || original == null
                || original.isBlank() || original.length() > 8000)
            return ResultadoFerramenta.texto("Objetivo ou pedido original invalido.");
        if (objetivo.matches("(?is).*(senha|password|pagamento|compra|cartao de credito).*"))
            return ResultadoFerramenta.texto("Tarefa sensivel proibida no computer use.");
        try {
            long usuario = contexto.usuarioAutenticado();
            String canal = contexto.canal();
            var confirmacao = intencoes.pedir("computer", "Tarefa no monitor principal: " + objetivo,
                    usuario, contexto.idPedido(), () -> tarefas.iniciar(objetivo, canal, usuario));
            return new ResultadoFerramenta("Confirme em NOVA mensagem: confirmo. Para desistir: cancelar.",
                    List.of(), List.of(), confirmacao, List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }
}
