package dev.jarvis.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cliente mínimo da Bot API do Telegram (https://core.telegram.org/bots/api),
 * feito só com o HttpClient do Java para você enxergar o que acontece por baixo.
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);
    /** O Telegram aceita até 4096 caracteres por mensagem; deixamos uma folga. */
    private static final int LIMITE_MENSAGEM = 4000;
    private static final int SEGUNDOS_LONG_POLLING = 30;

    private final HttpClient http;
    private final JsonMapper json;
    private final String urlBase;

    public TelegramClient(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.urlBase = "https://api.telegram.org/bot" + props.telegram().token() + "/";
    }

    /** Espera até 30s por mensagens novas (long polling) e devolve a lista de updates. */
    public List<JsonNode> buscarAtualizacoes(long offset) throws IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("offset", offset);
        corpo.put("timeout", SEGUNDOS_LONG_POLLING);
        corpo.putArray("allowed_updates").add("message");

        JsonNode resultado = chamar("getUpdates", corpo, Duration.ofSeconds(SEGUNDOS_LONG_POLLING + 15));
        List<JsonNode> atualizacoes = new ArrayList<>();
        for (int i = 0; i < resultado.size(); i++) {
            atualizacoes.add(resultado.get(i));
        }
        return atualizacoes;
    }

    /** Envia texto puro (sem Markdown, para não quebrar com caracteres especiais), dividindo se for longo. */
    public void enviarMensagem(long chatId, String texto) {
        String conteudo = texto == null || texto.isBlank() ? "(resposta vazia)" : texto;
        for (int inicio = 0; inicio < conteudo.length(); inicio += LIMITE_MENSAGEM) {
            String parte = conteudo.substring(inicio, Math.min(conteudo.length(), inicio + LIMITE_MENSAGEM));
            ObjectNode corpo = json.createObjectNode();
            corpo.put("chat_id", chatId);
            corpo.put("text", parte);
            try {
                chamar("sendMessage", corpo, Duration.ofSeconds(20));
            } catch (Exception e) {
                log.error("Falha ao enviar mensagem para o chat {}: {}", chatId, e.getMessage());
                return;
            }
        }
    }

    /** Mostra "digitando..." no Telegram enquanto o Jarvis pensa. Falhas aqui são ignoradas. */
    public void mostrarDigitando(long chatId) {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("chat_id", chatId);
        corpo.put("action", "typing");
        try {
            chamar("sendChatAction", corpo, Duration.ofSeconds(10));
        } catch (Exception e) {
            log.debug("Não consegui enviar 'digitando': {}", e.getMessage());
        }
    }

    private JsonNode chamar(String metodo, ObjectNode corpo, Duration timeout)
            throws IOException, InterruptedException {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(urlBase + metodo))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        JsonNode raiz = json.readTree(resposta.body());

        JsonNode ok = raiz.path("ok");
        if (!(ok.isBoolean() && ok.booleanValue())) {
            // Nunca logamos a URL: ela contém o token do bot.
            throw new IOException("Telegram recusou " + metodo + " (HTTP " + resposta.statusCode() + "): "
                    + Json.texto(raiz.path("description")));
        }
        return raiz.path("result");
    }
}
