package dev.jarvis.mcp;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O servidor MCP do Jarvis: o JSON-RPC que os agentes do escritório (Hermes Agent) falam.
 *
 * <p>Escrito à mão, sem SDK, pela regra do projeto para API HTTP simples: o transporte é o
 * <i>Streamable HTTP</i> no seu modo mais estreito — um POST por mensagem, resposta em JSON, sem
 * stream, sem sessão e sem nada que o servidor mande sozinho. Isso basta para três métodos
 * ({@code initialize}, {@code tools/list}, {@code tools/call}) mais o {@code ping}, que é tudo o que
 * um cliente precisa para descobrir e chamar ferramentas. Quem cuida de HTTP, token e Origin é o
 * {@code McpController} com o {@code TokenMcpFiltro}; aqui só entra o corpo, e só sai o corpo.
 *
 * <p>Os erros seguem a divisão do protocolo: mensagem que não é JSON-RPC válido, método
 * desconhecido e ferramenta inexistente são erro JSON-RPC; argumento errado e falha ao executar vão
 * no resultado, com {@code isError}, para o modelo do agente ler e tentar de novo.
 */
@Component
public class ServidorMcp {

    private static final Logger log = LoggerFactory.getLogger(ServidorMcp.class);

    /** Da mais nova para a mais antiga. Cliente que pede outra recebe a primeira, e decide. */
    static final List<String> VERSOES = List.of("2025-11-25", "2025-06-18", "2025-03-26");
    static final String NOME = "jarvis";
    static final String VERSAO_DO_SERVIDOR = "1.0.0";

    static final int ERRO_JSON = -32700;
    static final int ERRO_REQUISICAO = -32600;
    static final int ERRO_METODO = -32601;
    static final int ERRO_PARAMETROS = -32602;

    private static final String INSTRUCOES = "Ferramentas do Jarvis, o assistente pessoal do dono. "
            + "Use avisar_aprovacoes para avisar no Telegram que há itens esperando a aprovação dele "
            + "no quadro do escritório, e as ferramentas financeiras para lançar e consultar gastos e "
            + "ganhos. Nada aqui envia mensagem a terceiros.";

    private final JsonMapper json;
    /** Ordem alfabética pelo motivo da lista de ferramentas da conversa: estável entre deploys. */
    private final Map<String, FerramentaMcp> porNome = new TreeMap<>();

    public ServidorMcp(JsonMapper json, List<FerramentaMcp> ferramentas) {
        this.json = json;
        for (FerramentaMcp f : ferramentas) {
            if (porNome.put(f.nome(), f) != null) {
                throw new IllegalStateException("Duas ferramentas MCP com o nome " + f.nome());
            }
        }
    }

    /**
     * Trata uma mensagem JSON-RPC.
     *
     * @return a resposta, ou vazio para notificação (que não tem resposta: o HTTP devolve 202)
     */
    public Optional<String> tratar(String corpo) {
        JsonNode mensagem;
        try {
            mensagem = json.readTree(corpo == null ? "" : corpo);
        } catch (RuntimeException e) {
            // No Jackson 3 o erro de leitura é JacksonException, que é RuntimeException.
            return Optional.of(erro(null, ERRO_JSON, "JSON inválido."));
        }
        if (mensagem == null || !mensagem.isObject()) {
            return Optional.of(erro(null, ERRO_REQUISICAO,
                    "Mande um objeto JSON-RPC por requisição (sem lote)."));
        }
        String metodo = Json.texto(mensagem.path("method"));
        boolean temId = mensagem.has("id");
        JsonNode id = mensagem.get("id");
        if (!temId) {
            // Notificação (notifications/initialized, cancelled...) ou resposta do cliente a algo
            // que nunca perguntamos: nada a responder.
            return Optional.empty();
        }
        if (id == null || !(id.isString() || id.isNumber())) {
            return Optional.of(erro(null, ERRO_REQUISICAO, "O id tem de ser texto ou número."));
        }
        if (metodo.isEmpty()) {
            return Optional.of(erro(id, ERRO_REQUISICAO, "Falta o campo method."));
        }
        JsonNode params = mensagem.path("params");
        return Optional.of(switch (metodo) {
            case "initialize" -> resposta(id, inicializar(params));
            case "ping" -> resposta(id, json.createObjectNode());
            case "tools/list" -> resposta(id, listar());
            case "tools/call" -> chamar(id, params);
            default -> erro(id, ERRO_METODO, "Método desconhecido: " + limitar(metodo));
        });
    }

    private ObjectNode inicializar(JsonNode params) {
        String pedida = Json.texto(params.path("protocolVersion"));
        String versao = VERSOES.contains(pedida) ? pedida : VERSOES.get(0);
        ObjectNode resultado = json.createObjectNode().put("protocolVersion", versao);
        resultado.putObject("capabilities").putObject("tools").put("listChanged", false);
        resultado.putObject("serverInfo").put("name", NOME).put("version", VERSAO_DO_SERVIDOR);
        resultado.put("instructions", INSTRUCOES);
        return resultado;
    }

    private ObjectNode listar() {
        ObjectNode resultado = json.createObjectNode();
        ArrayNode lista = resultado.putArray("tools");
        for (FerramentaMcp f : porNome.values()) {
            ObjectNode item = lista.addObject().put("name", f.nome()).put("description", f.descricao());
            item.set("inputSchema", f.esquema());
        }
        return resultado;
    }

    private String chamar(JsonNode id, JsonNode params) {
        String nome = Json.texto(params.path("name"));
        FerramentaMcp ferramenta = porNome.get(nome);
        if (ferramenta == null) {
            return erro(id, ERRO_PARAMETROS, "Ferramenta desconhecida: " + limitar(nome)
                    + ". Conheço " + String.join(", ", porNome.keySet()) + ".");
        }
        JsonNode argumentos = params.path("arguments");
        if (!argumentos.isObject()) {
            argumentos = json.createObjectNode();
        }
        ResultadoMcp resultado;
        try {
            resultado = ferramenta.executar(argumentos);
        } catch (Exception e) {
            // O detalhe fica no log do servidor; ao agente vai só que falhou.
            log.warn("MCP: {} falhou ({})", nome, e.getMessage());
            resultado = ResultadoMcp.erro("Não consegui executar " + nome + " agora. Tente mais tarde.");
        }
        log.info("MCP: {} {}", nome, resultado.erro() ? "recusada" : "ok");
        ObjectNode corpo = json.createObjectNode();
        corpo.putArray("content").addObject().put("type", "text").put("text", resultado.texto());
        corpo.put("isError", resultado.erro());
        return resposta(id, corpo);
    }

    private String resposta(JsonNode id, ObjectNode resultado) {
        ObjectNode raiz = json.createObjectNode().put("jsonrpc", "2.0");
        raiz.set("id", id);
        raiz.set("result", resultado);
        return json.writeValueAsString(raiz);
    }

    private String erro(JsonNode id, int codigo, String mensagem) {
        ObjectNode raiz = json.createObjectNode().put("jsonrpc", "2.0");
        if (id == null) {
            raiz.putNull("id");
        } else {
            raiz.set("id", id);
        }
        raiz.putObject("error").put("code", codigo).put("message", mensagem);
        return json.writeValueAsString(raiz);
    }

    /** Texto do cliente que volta numa mensagem de erro: curto e sem controle. */
    private static String limitar(String texto) {
        String limpo = texto == null ? "" : texto.replaceAll("\\p{Cntrl}", "");
        return limpo.length() > 60 ? limpo.substring(0, 60) + "…" : limpo;
    }
}
