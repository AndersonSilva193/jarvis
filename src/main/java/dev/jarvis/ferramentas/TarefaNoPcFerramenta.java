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
        return "Ultimo recurso para operar visualmente o monitor principal do PC. Use somente "
                + "quando resposta direta, busca, criar_arquivo, rotina cadastrada e acao fixa "
                + "nao resolverem. Descreva objetivo e mudancas permitidas em escopo curto. "
                + "A tarefa so inicia apos nova mensagem autenticada do usuario. "
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
            return new ResultadoFerramenta("Confirme em NOVA mensagem: confirmo pc "
                    + confirmacao.codigo(), List.of(), List.of(), confirmacao, List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }
}
