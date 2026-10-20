package dev.jarvis.ia;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cliente mínimo da Messages API do Claude (https://platform.claude.com/docs).
 * Uma chamada = manda o histórico + a lista de ferramentas e recebe a próxima resposta do modelo.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final URI URL = URI.create("https://api.anthropic.com/v1/messages");
    /** Erros temporários (limite de taxa, sobrecarga) que valem uma nova tentativa. */
    private static final Set<Integer> STATUS_TEMPORARIOS = Set.of(429, 500, 502, 503, 529);
    private static final int TENTATIVAS = 3;
    /** Prazo de uma conversa normal: o modelo pode pensar e chamar ferramenta antes de responder. */
    private static final Duration PRAZO_PADRAO = Duration.ofSeconds(120);

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Anthropic config;

    public ClaudeClient(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.config = props.anthropic();
    }

    /** Versão simples, com o system num bloco de texto só e sem cache. */
    public JsonNode criarMensagem(String system, ArrayNode mensagens, ArrayNode ferramentas)
            throws IOException, InterruptedException {
        ArrayNode blocos = json.createArrayNode();
        blocos.addObject().put("type", "text").put("text", system);
        return criarMensagem(blocos, mensagens, ferramentas);
    }

    /**
     * Versão com o system em blocos, que é o que permite marcar o bloco fixo com
     * {@code cache_control} e pagar o texto da personalidade uma vez só.
     */
    public JsonNode criarMensagem(ArrayNode system, ArrayNode mensagens, ArrayNode ferramentas)
            throws IOException, InterruptedException {
        return criarMensagem(system, mensagens, ferramentas, config.maxTokens(), PRAZO_PADRAO,
                TENTATIVAS);
    }

    /**
     * Chamada curta de decisão: teto de tokens e prazo próprios, tentativa única.
     *
     * <p>Sem repetição de propósito: a espera entre tentativas é de segundos e estouraria o prazo
     * curto de quem chama assim. Quem decide em poucos tokens prefere falhar rápido — e tratar a
     * falha — a segurar o turno do usuário.
     */
    public JsonNode criarMensagem(ArrayNode system, ArrayNode mensagens, ArrayNode ferramentas,
            int maxTokens, Duration prazo) throws IOException, InterruptedException {
        return criarMensagem(system, mensagens, ferramentas, maxTokens, prazo, 1);
    }

    private JsonNode criarMensagem(ArrayNode system, ArrayNode mensagens, ArrayNode ferramentas,
            int maxTokens, Duration prazo, int tentativas)
            throws IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", config.modelo());
        corpo.put("max_tokens", maxTokens);
        corpo.set("system", system);
        corpo.set("messages", mensagens);
        if (!ferramentas.isEmpty()) {
            corpo.set("tools", ferramentas);
        }

        HttpRequest requisicao = HttpRequest.newBuilder(URL)
                .timeout(prazo)
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            int status = resposta.statusCode();
            if (status == 200) {
                JsonNode raiz = json.readTree(resposta.body());
                registrarConsumo(raiz.path("usage"));
                return raiz;
            }
            if (STATUS_TEMPORARIOS.contains(status) && tentativa < tentativas) {
                log.warn("API do Claude respondeu {} (tentativa {}), tentando de novo...", status, tentativa);
                Thread.sleep(2_000L * tentativa);
                continue;
            }
            throw new ErroDaApiDoModelo(status, detalheSeguro(resposta.body()));
        }
    }

    /**
     * Mostra no log quanto a chamada consumiu. Os dois campos de cache só aparecem quando há cache:
     * {@code cache_escrito} na primeira chamada (quando o prefixo é gravado) e {@code cache_lido}
     * nas seguintes. Se as duas ficarem sempre em zero, o bloco fixo não está sendo cacheado.
     */
    private void registrarConsumo(JsonNode usage) {
        log.info("Claude: entrada={} saída={} cache_escrito={} cache_lido={}",
                Json.numero(usage.path("input_tokens")),
                Json.numero(usage.path("output_tokens")),
                Json.numero(usage.path("cache_creation_input_tokens")),
                Json.numero(usage.path("cache_read_input_tokens")));
    }

    private String detalheSeguro(String corpo) {
        try {
            JsonNode erro = json.readTree(corpo).path("error");
            String tipo = Json.texto(erro.path("type"));
            String mensagem = Json.texto(erro.path("message"));
            if (mensagem.isBlank()) return tipo.isBlank() ? "sem detalhe do provedor" : tipo;
            mensagem = mensagem.replaceAll("[\\r\\n\\p{Cntrl}]", " ")
                    .replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [oculto]")
                    .replaceAll("(?i)sk-[a-z0-9_-]+", "[chave oculta]");
            if (mensagem.length() > 240) mensagem = mensagem.substring(0, 240) + "…";
            return (tipo.isBlank() ? "" : tipo + ": ") + mensagem;
        } catch (RuntimeException e) {
            return "resposta de erro sem detalhe legível";
        }
    }
}
