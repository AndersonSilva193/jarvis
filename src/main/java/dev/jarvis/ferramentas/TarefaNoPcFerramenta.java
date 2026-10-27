package dev.jarvis.ferramentas;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Pede autorizacao humana para uma tarefa visual delimitada no PC. */
@Component
public class TarefaNoPcFerramenta implements Ferramenta {
    private static final Pattern ACAO_SENSIVEL = Pattern.compile(
            "\\b(?:comprar|compre|pagar|pague|adquirir|adquira|"
            + "(?:fazer|realizar|efetuar|finalizar)\\s+(?:(?:uma|um|o|a)\\s+)?(?:compra|pagamento)|"
            + "(?:digitar|inserir|preencher|informar|enviar|usar)\\s+"
            + "(?:(?:a|o|uma|um|minha|meu)\\s+)?(?:senha|password|cartao de credito))\\b");
    private static final Pattern NEGACAO_SENSIVEL = Pattern.compile(
            "\\b(?:nao|nem|sem)\\s+(?:(?:fazer|realizar|efetuar|finalizar|digitar|inserir|"
            + "preencher|informar|enviar|usar)\\s+)?(?:comprar|compre|compras?|pagar|pague|"
            + "pagamentos?|senha|password|cartao de credito)\\b");
    private final TarefasComputerUse tarefas;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;
    public TarefaNoPcFerramenta(TarefasComputerUse tarefas, Aprovacoes aprovacoes, JsonMapper json) {
        this.tarefas = tarefas; this.aprovacoes = aprovacoes; this.json = json;
    }
    @Override public String nome() { return "tarefa_no_pc"; }
    /**
     * Olha o {@code configurado()}, e não o {@code disponivel()}, de propósito: este texto vai para
     * o array {@code tools}, e o {@code disponivel()} embute uma janela de 90 segundos de contato
     * com o agente — a descrição trocaria a cada batida de coração perdida e levaria o prefixo do
     * cache de prompt com ela. Com o PC fora do ar quem recusa é a execução, logo abaixo.
     */
    @Override public String descricao() {
        if (!tarefas.configurado()) return "Computer use desativado na configuração do Jarvis. "
                + "Não prometa executar tarefa visual.";
        return "Último recurso para executar tarefa pela interface visual do monitor principal do PC. "
                + "Se o usuário pediu uma ação que pode ser feita no PC e resposta direta, busca, "
                + "criar_arquivo, rotina cadastrada ou ação fixa não resolverem, use esta ferramenta "
                + "para pedir confirmação. Não diga que falta ferramenta antes de tentar este caminho. "
                + "A lista de apps de controlar_pc/abrir_app não limita o uso visual do menu Iniciar. "
                + "Descreva objetivo e mudanças permitidas em escopo curto. "
                + "A ferramenta devolve uma pergunta de aprovação e a tarefa só inicia depois que o "
                + "usuário responder que sim; não peça palavra, código nem frase exata. "
                + "Novo pedido autenticado substitui uma confirmação anterior ainda pendente; "
                + "se o usuário pedir cancelamento explícito, use cancelar_tarefa_no_pc sem ID antes. "
                + "Nunca use para efetuar pagamentos, compras, inserir credenciais ou abrir shell. "
                + "Um app ter loja não proíbe apenas abri-lo.";
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("objetivo").put("type", "string")
                .put("description", "Acao positiva e concreta pedida pelo usuario, sem segredo nem "
                        + "lista de proibicoes. Ex.: abrir Battle.net pelo menu Iniciar.");
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
            return ResultadoFerramenta.texto("Objetivo ou pedido original inválido.");
        if (acaoSensivel(original) || acaoSensivel(objetivo))
            return ResultadoFerramenta.texto("Tarefa sensível proibida no computer use.");
        try {
            long usuario = contexto.usuarioAutenticado();
            String canal = contexto.canal();
            String pergunta = "Vou usar o monitor principal para: " + objetivo + ". Posso?";
            var confirmacao = aprovacoes.pedir("computer", "Tarefa no monitor principal: " + objetivo,
                    pergunta, Aprovacoes.Risco.COMUM, usuario, contexto.idPedido(),
                    () -> tarefas.iniciar(objetivo, canal, usuario));
            return new ResultadoFerramenta(pergunta,
                    List.of(), List.of(), confirmacao, List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }

    static boolean acaoSensivel(String texto) {
        String semAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT);
        return ACAO_SENSIVEL.matcher(NEGACAO_SENSIVEL.matcher(semAcentos)
                .replaceAll(" ")).find();
    }
}
