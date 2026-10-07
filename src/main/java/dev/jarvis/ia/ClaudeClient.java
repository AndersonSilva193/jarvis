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

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Anthropic config;

    public ClaudeClient(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.config = props.anthropic();
    }

    public JsonNode criarMensagem(String system, ArrayNode mensagens, ArrayNode ferramentas)
            throws IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", config.modelo());
        corpo.put("max_tokens", config.maxTokens());
        corpo.put("system", system);
        corpo.set("messages", mensagens);
        if (!ferramentas.isEmpty()) {
            corpo.set("tools", ferramentas);
        }

        HttpRequest requisicao = HttpRequest.newBuilder(URL)
                .timeout(Duration.ofSeconds(120))
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            int status = resposta.statusCode();
            if (status == 200) {
                return json.readTree(resposta.body());
            }
            if (STATUS_TEMPORARIOS.contains(status) && tentativa < TENTATIVAS) {
                log.warn("API do Claude respondeu {} (tentativa {}), tentando de novo...", status, tentativa);
                Thread.sleep(2_000L * tentativa);
                continue;
            }
            throw new IOException("API do Claude respondeu HTTP " + status + ": " + resumir(resposta.body()));
        }
    }

    private static String resumir(String corpo) {
        return corpo == null ? "" : corpo.length() > 300 ? corpo.substring(0, 300) + "..." : corpo;
    }
}
