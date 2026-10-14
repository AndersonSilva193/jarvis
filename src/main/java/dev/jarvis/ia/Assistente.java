package dev.jarvis.ia;

import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O "cérebro": recebe o pedido, conversa com o Claude e executa as ferramentas que ele pedir,
 * repetindo até o modelo dar a resposta final (o chamado "loop de agente").
 */
@Service
public class Assistente {

    /** Limite de idas e vindas com ferramentas por pedido, para nunca entrar em loop infinito. */
    private static final int MAX_PASSOS = 8;
    /** Quantas trocas (pedido + resposta) o Jarvis lembra por conversa. Fica em memória. */
    private static final int MAX_TURNOS_HISTORICO = 10;

    private final ClaudeClient claude;
    private final Ferramentas ferramentas;
    private final PersonalidadeService personalidade;
    private final JsonMapper json;
    private final JarvisProperties props;

    /** Cada turno é a lista de mensagens de um pedido (texto do usuário, chamadas de ferramenta e resposta). */
    private final Map<Long, Deque<List<ObjectNode>>> historicos = new HashMap<>();

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, PersonalidadeService personalidade,
            JsonMapper json, JarvisProperties props) {
        this.claude = claude;
        this.ferramentas = ferramentas;
        this.personalidade = personalidade;
        this.json = json;
        this.props = props;
    }

    /** Responde a uma mensagem vinda do Telegram. */
    public String responder(long chatId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM);
    }

    /** Responde a uma mensagem da conversa, usando e atualizando o histórico daquele chat. */
    public synchronized String responder(long chatId, String texto, Canal canal) throws Exception {
        Deque<List<ObjectNode>> historico = historicos.computeIfAbsent(chatId, id -> new ArrayDeque<>());
        List<ObjectNode> turno = new ArrayList<>();
        turno.add(mensagemDoUsuario(texto));

        String resposta = executarLoop(historico, turno, canal);

        // Só guardamos o turno se ele terminou bem, assim o histórico nunca fica "pela metade".
        historico.addLast(turno);
        while (historico.size() > MAX_TURNOS_HISTORICO) {
            historico.removeFirst();
        }
        return resposta;
    }

    /** Executa uma instrução avulsa sem misturar com o histórico da conversa. */
    public String executarTarefa(String instrucao) throws Exception {
        return executarTarefa(instrucao, Canal.TAREFA);
    }

    /**
     * Executa uma instrução avulsa (ex.: tarefas agendadas, briefing falado, HUD) sem misturar com o
     * histórico da conversa. O canal decide só o formato do texto que volta.
     */
    public synchronized String executarTarefa(String instrucao, Canal canal) throws Exception {
        List<ObjectNode> turno = new ArrayList<>();
        turno.add(mensagemDoUsuario(instrucao));
        return executarLoop(new ArrayDeque<>(), turno, canal);
    }

    public synchronized void limparHistorico(long chatId) {
        historicos.remove(chatId);
    }

    private String executarLoop(Deque<List<ObjectNode>> historico, List<ObjectNode> turno, Canal canal)
            throws Exception {
        for (int passo = 0; passo < MAX_PASSOS; passo++) {
            ArrayNode mensagens = json.createArrayNode();
            for (List<ObjectNode> turnoAnterior : historico) {
                for (ObjectNode m : turnoAnterior) {
                    mensagens.add(m);
                }
            }
            for (ObjectNode m : turno) {
                mensagens.add(m);
            }

            JsonNode resposta = claude.criarMensagem(blocosDeSistema(canal), mensagens, ferramentas.definicoes());
            JsonNode conteudo = resposta.path("content");

            ObjectNode doAssistente = json.createObjectNode();
            doAssistente.put("role", "assistant");
            if (conteudo.isEmpty()) {
                // A API não aceita mensagem vazia no histórico.
                doAssistente.put("content", "(sem resposta)");
            } else {
                doAssistente.set("content", conteudo);
            }
            turno.add(doAssistente);

            if (!"tool_use".equals(Json.texto(resposta.path("stop_reason")))) {
                return extrairTexto(conteudo);
            }

            // O modelo pediu uma ou mais ferramentas: executamos e devolvemos os resultados.
            ArrayNode resultados = json.createArrayNode();
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                if ("tool_use".equals(Json.texto(bloco.path("type")))) {
                    resultados.add(ferramentas.executar(bloco));
                }
            }
            ObjectNode comResultados = json.createObjectNode();
            comResultados.put("role", "user");
            comResultados.set("content", resultados);
            turno.add(comResultados);
        }
        throw new IllegalStateException("precisei de passos demais para resolver isso. Tente um pedido mais simples.");
    }

    private ObjectNode mensagemDoUsuario(String texto) {
        ObjectNode m = json.createObjectNode();
        m.put("role", "user");
        m.put("content", texto);
        return m;
    }

    private String extrairTexto(JsonNode conteudo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conteudo.size(); i++) {
            JsonNode bloco = conteudo.get(i);
            if ("text".equals(Json.texto(bloco.path("type")))) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(Json.texto(bloco.path("text")));
            }
        }
        return sb.isEmpty() ? "(sem resposta)" : sb.toString().trim();
    }

    /**
     * O system em dois blocos: a personalidade (fixa, cacheada) e o contexto do pedido (dinâmico).
     * O cache_control só vai quando o bloco fixo passa do mínimo do modelo — abaixo disso a API o
     * ignoraria em silêncio.
     */
    private ArrayNode blocosDeSistema(Canal canal) {
        String texto = personalidade.texto();
        boolean cachear = PersonalidadeService.estimarTokens(texto) >= props.anthropic().cacheMinimoTokens();
        return PromptDeSistema.montar(json, texto, cachear, canal, props.nomeUsuario(),
                ZonedDateTime.now(props.zona()));
    }
}
