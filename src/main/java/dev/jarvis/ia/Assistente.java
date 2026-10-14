package dev.jarvis.ia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import dev.jarvis.conversa.*;
import dev.jarvis.ferramentas.ResultadoFerramenta;

import org.springframework.stereotype.Service;

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
    private final SistemaDoJarvis sistema;
    private final JsonMapper json;
    private final RegistroConversas conversas;
    private final AnexosService anexos;
    private final ReentrantLock execucao = new ReentrantLock(true);

    /** Cada turno é a lista de mensagens de um pedido (texto do usuário, chamadas de ferramenta e resposta). */
    private final Map<Long, Deque<List<ObjectNode>>> historicos = new HashMap<>();

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json) {
        this(claude, ferramentas, sistema, json, new RegistroConversas(evento -> { }), new AnexosService());
    }

    @Autowired
    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos) {
        this.claude = claude;
        this.ferramentas = ferramentas;
        this.sistema = sistema;
        this.json = json;
        this.conversas = conversas;
        this.anexos = anexos;
    }

    /** Responde a uma mensagem vinda do Telegram. */
    public String responder(long chatId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM);
    }

    /** Responde a uma mensagem da conversa, usando e atualizando o histórico daquele chat. */
    public String responder(long chatId, String texto, Canal canal) throws Exception {
        return responder(chatId, texto, canal, null).texto();
    }

    public RespostaConversa responder(long chatId, String texto, Canal canal, OuvinteProgresso ouvinte) throws Exception {
        OuvinteProgresso progresso = ouvinte == null ? new OuvinteProgresso() { } : ouvinte;
        String id = progresso.idPedido() == null ? UUID.randomUUID().toString() : progresso.idPedido();
        execucao.lockInterruptibly();
        try {
            if (progresso.idPedido() != null && !conversas.pendente(chatId, id))
                throw new IllegalStateException("Pedido encerrado pela limpeza do histórico");
            OuvinteProgresso.avisar(progresso::iniciado);
            Deque<List<ObjectNode>> historico = historicos.computeIfAbsent(chatId, chave -> new ArrayDeque<>());
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(texto));

            List<ResultadoFerramenta> resultados = new ArrayList<>();
            String resposta = executarLoop(historico, turno, canal, progresso, resultados);
            List<Anexo> publicados = new ArrayList<>();
            Confirmacao confirmacao = null;
            for (ResultadoFerramenta resultado : resultados) {
                for (var artefato : resultado.artefatos()) {
                    try { publicados.add(anexos.registrar(artefato)); }
                    catch (java.io.IOException e) { resposta += "\nNão consegui disponibilizar um dos anexos."; }
                }
                for (var referencia : resultado.referencias())
                    anexos.referencia(referencia.nome(), referencia.url()).ifPresent(publicados::add);
                if (resultado.confirmacao() != null) confirmacao = resultado.confirmacao();
            }
            RespostaConversa publica = new RespostaConversa(id, canal.nome(), resposta, publicados,
                    confirmacao, Instant.now().toString(), null);

            // Só guardamos o turno se ele terminou bem, assim o histórico nunca fica "pela metade".
            historico.addLast(semPensamentos(turno));
            while (historico.size() > MAX_TURNOS_HISTORICO) {
                historico.removeFirst();
            }
            conversas.concluir(chatId, texto, publica);
            return publica;
        } finally { execucao.unlock(); }
    }

    /** Executa uma instrução avulsa sem misturar com o histórico da conversa. */
    public String executarTarefa(String instrucao) throws Exception {
        return executarTarefa(instrucao, Canal.TAREFA);
    }

    /**
     * Executa uma instrução avulsa (ex.: tarefas agendadas, briefing falado, HUD) sem misturar com o
     * histórico da conversa. O canal decide só o formato do texto que volta.
     */
    public String executarTarefa(String instrucao, Canal canal) throws Exception {
        execucao.lockInterruptibly();
        try {
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(instrucao));
            return executarLoop(new ArrayDeque<>(), turno, canal, new OuvinteProgresso() { }, new ArrayList<>());
        } finally { execucao.unlock(); }
    }

    public void limparHistorico(long chatId) {
        execucao.lock();
        try {
            historicos.remove(chatId);
            conversas.limpar(chatId);
        } finally { execucao.unlock(); }
    }

    private String executarLoop(Deque<List<ObjectNode>> historico, List<ObjectNode> turno, Canal canal,
            OuvinteProgresso progresso, List<ResultadoFerramenta> detalhados)
            throws Exception {
        // Montado UMA vez por pedido, de propósito. O bloco dinâmico tem a hora: remontá-lo a cada
        // ida e vinda de ferramenta mudaria o system no meio do turno, e o modelo rejeita (HTTP 400)
        // os blocos de pensamento que ele devolveu sob o system anterior.
        ArrayNode system = sistema.blocos(canal);

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

            JsonNode resposta = claude.criarMensagem(system, mensagens, ferramentas.definicoes());
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
                    OuvinteProgresso seguro = new OuvinteProgresso() {
                        @Override public void ferramenta(String nome, String descricao, Integer atual, Integer total) {
                            OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, atual, total));
                        }
                    };
                    resultados.add(ferramentas.executar(bloco, seguro, detalhados::add));
                }
            }
            ObjectNode comResultados = json.createObjectNode();
            comResultados.put("role", "user");
            comResultados.set("content", resultados);
            turno.add(comResultados);
        }
        throw new IllegalStateException("precisei de passos demais para resolver isso. Tente um pedido mais simples.");
    }

    /**
     * Copia o turno sem os blocos de pensamento ("thinking") do modelo, que é a forma que guardamos
     * no histórico.
     *
     * <p>Cada bloco desses vem assinado e preso ao prefixo exato que o produziu — system, ferramentas
     * e mensagens anteriores. Como o nosso system carrega a hora e o histórico tem janela deslizante
     * (os turnos mais velhos caem fora), esse prefixo muda entre um pedido e outro, e a API recusa o
     * bloco com HTTP 400 ("block is bound to a different conversation"). Dentro do turno em curso eles
     * continuam intactos, que é o que o laço de ferramentas exige; o que sai é sempre tudo que é
     * anterior, nunca um pedaço do meio.
     */
    private List<ObjectNode> semPensamentos(List<ObjectNode> turno) {
        List<ObjectNode> limpo = new ArrayList<>(turno.size());
        for (ObjectNode mensagem : turno) {
            JsonNode conteudo = mensagem.path("content");
            if (!conteudo.isArray()) {
                limpo.add(mensagem);
                continue;
            }
            ArrayNode mantidos = json.createArrayNode();
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                String tipo = Json.texto(bloco.path("type"));
                if (!"thinking".equals(tipo) && !"redacted_thinking".equals(tipo)) {
                    mantidos.add(bloco);
                }
            }
            ObjectNode copia = mensagem.deepCopy();
            // Mensagem sem conteúdo nenhum a API recusa; sobra o texto de marcação.
            if (mantidos.isEmpty()) {
                copia.put("content", "(sem resposta)");
            } else {
                copia.set("content", mantidos);
            }
            limpo.add(copia);
        }
        return limpo;
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

}
