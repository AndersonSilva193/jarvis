package dev.jarvis.telegram;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    /** O sendPhoto do Telegram aceita até 10 MB de imagem; acima disso ele recusa. */
    private static final int LIMITE_FOTO = 10 * 1024 * 1024;
    /** E até 1024 caracteres de legenda. */
    private static final int LIMITE_LEGENDA = 1024;

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

    /**
     * Envia a resposta convertendo o markdown simples do Jarvis para o HTML do Telegram, dividindo
     * em várias mensagens se for longa. Se o Telegram recusar a formatação, o mesmo pedaço vai de
     * novo em texto puro: antes uma mensagem sem negrito do que nenhuma mensagem.
     */
    public void enviarMensagem(long chatId, String texto) {
        String conteudo = texto == null || texto.isBlank() ? "(resposta vazia)" : texto;
        for (String parte : MarkdownTelegram.dividir(conteudo, LIMITE_MENSAGEM)) {
            try {
                enviarPedaco(chatId, MarkdownTelegram.paraHtml(parte), "HTML");
            } catch (TelegramRecusouException e) {
                // 400 é como o Telegram devolve "can't parse entities". Outras recusas (chat
                // inexistente, limite de taxa) não melhoram sem formatação, então nem tentamos.
                if (e.status() != 400) {
                    log.error("Telegram recusou a mensagem do chat {}: {}", chatId, e.descricao());
                    return;
                }
                log.warn("Telegram recusou o HTML ({}); reenviando em texto puro.", e.descricao());
                try {
                    enviarPedaco(chatId, parte, null);
                } catch (Exception falha) {
                    log.error("Falha ao reenviar em texto puro para o chat {}: {}", chatId, falha.getMessage());
                    return;
                }
            } catch (Exception e) {
                log.error("Falha ao enviar mensagem para o chat {}: {}", chatId, e.getMessage());
                return;
            }
        }
    }

    private void enviarPedaco(long chatId, String conteudo, String formato)
            throws IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("chat_id", chatId);
        corpo.put("text", conteudo);
        if (formato != null) {
            corpo.put("parse_mode", formato);
        }
        chamar("sendMessage", corpo, Duration.ofSeconds(20));
    }

    /**
     * Envia uma foto (hoje, o print do PC) por {@code sendPhoto} multipart.
     *
     * <p>Não reaproveita o {@link #chamar} de propósito: aquele é {@code application/json} com
     * corpo de texto, e parametrizá-lo para dois tipos de corpo sairia mais confuso do que repetir
     * as cinco linhas de conferência do {@code ok}.
     *
     * <p>Timeout de 20 s, igual ao do envio de texto e não mais: isto roda dentro do lock do
     * {@code Assistente}, que atende um pedido por vez.
     *
     * @throws java.io.IOException se o Telegram recusar ({@link TelegramRecusouException}), se a
     *                             imagem estiver vazia ou se passar do limite — quem chama decide
     *                             como degradar
     */
    public void enviarFoto(long chatId, byte[] imagem, String nomeDoArquivo, String legenda)
            throws IOException, InterruptedException {
        if (imagem == null || imagem.length == 0) {
            throw new IOException("imagem vazia");
        }
        if (imagem.length > LIMITE_FOTO) {
            throw new IOException("imagem de " + imagem.length / 1024 + " KB passa do limite do sendPhoto");
        }
        String nome = nomeDoArquivo == null || nomeDoArquivo.isBlank() ? "imagem.png" : nomeDoArquivo;
        String mime = nome.endsWith(".jpg") || nome.endsWith(".jpeg") ? "image/jpeg" : "image/png";

        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("chat_id", String.valueOf(chatId));
        if (legenda != null && !legenda.isBlank()) {
            campos.put("caption", legenda.length() > LIMITE_LEGENDA
                    ? legenda.substring(0, LIMITE_LEGENDA) : legenda);
        }

        String boundary = MultipartTelegram.boundary();
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(urlBase + "sendPhoto"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(
                        MultipartTelegram.corpo(boundary, campos, "photo", nome, mime, imagem)))
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        JsonNode raiz = json.readTree(resposta.body());
        JsonNode ok = raiz.path("ok");
        if (!(ok.isBoolean() && ok.booleanValue())) {
            throw new TelegramRecusouException("sendPhoto", resposta.statusCode(),
                    Json.texto(raiz.path("description")));
        }
    }

    /** Entrega um arquivo gerado, sem converter seus bytes em base64 no JSON. */
    public void enviarDocumento(long chatId, byte[] bytes, String nome) throws IOException, InterruptedException {
        if (bytes == null || bytes.length == 0 || bytes.length > 8 * 1024 * 1024)
            throw new IOException("documento vazio ou grande demais");
        String boundary = MultipartTelegram.boundary();
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(urlBase + "sendDocument"))
                .timeout(Duration.ofSeconds(45))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(MultipartTelegram.corpo(boundary,
                        Map.of("chat_id", String.valueOf(chatId)), "document", nome,
                        "application/octet-stream", bytes))).build();
        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        JsonNode raiz = json.readTree(resposta.body());
        if (!raiz.path("ok").asBoolean(false))
            throw new TelegramRecusouException("sendDocument", resposta.statusCode(),
                    Json.texto(raiz.path("description")));
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
            throw new TelegramRecusouException(metodo, resposta.statusCode(), Json.texto(raiz.path("description")));
        }
        return raiz.path("result");
    }
}
