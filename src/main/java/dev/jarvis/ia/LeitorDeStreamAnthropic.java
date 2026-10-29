package dev.jarvis.ia;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.function.Consumer;

import dev.jarvis.util.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lê o SSE de uma chamada {@code "stream": true} da Messages API e remonta, no fim, a mesma
 * forma de uma resposta sem stream: {@code content} com um bloco de texto, {@code stop_reason} e
 * {@code usage}. Assim o {@code ConversorAnthropic} converte as duas do mesmo jeito.
 *
 * <p>Só o texto interessa a quem transmite: cada {@code text_delta} vai para {@code aoTexto} na
 * hora. Pensamento ({@code thinking_delta}, {@code signature_delta}), {@code ping} e qualquer evento
 * novo são ignorados — evento desconhecido não pode derrubar um stream que está funcionando.
 *
 * <p>Os números de uso chegam em dois pedaços: entrada e cache no {@code message_start}, saída
 * (acumulada) no {@code message_delta}. Um evento {@code error} no meio do stream — a API
 * sobrecarregou depois de começar — vira {@link ErroDaApiDoModelo}, como um erro HTTP.
 */
final class LeitorDeStreamAnthropic {

    private final JsonMapper json;

    LeitorDeStreamAnthropic(JsonMapper json) {
        this.json = json;
    }

    /** O que se leu até o fim (ou até o corte): a resposta remontada. */
    static final class Leitura {
        final StringBuilder texto = new StringBuilder();
        String motivo = "";
        long entrada;
        long saida;
        long cacheEscrito;
        long cacheLido;
        boolean terminou;
    }

    /**
     * Lê até o {@code message_stop} ou o fim do corpo. A {@link Leitura} é preenchida conforme os
     * eventos chegam, então quem passou uma e teve o stream cortado ainda sabe o que foi gasto.
     */
    void ler(BufferedReader leitor, Consumer<String> aoTexto, Leitura leitura) throws IOException {
        String evento = "";
        StringBuilder dados = new StringBuilder();
        String linha;
        while ((linha = leitor.readLine()) != null) {
            if (linha.isEmpty()) {
                if (!dados.isEmpty() && tratar(evento, dados.toString(), aoTexto, leitura)) {
                    return;
                }
                evento = "";
                dados.setLength(0);
            } else if (linha.startsWith("event:")) {
                evento = linha.substring(6).trim();
            } else if (linha.startsWith("data:")) {
                if (!dados.isEmpty()) dados.append('\n');
                dados.append(linha.substring(5).stripLeading());
            }
            // Comentário (":") e campo desconhecido: nada a fazer.
        }
        if (!dados.isEmpty()) {
            tratar(evento, dados.toString(), aoTexto, leitura);
        }
    }

    /** Verdadeiro no {@code message_stop}: não há mais nada a ler. */
    private boolean tratar(String evento, String dados, Consumer<String> aoTexto, Leitura leitura)
            throws ErroDaApiDoModelo {
        JsonNode no;
        try {
            no = json.readTree(dados);
        } catch (JacksonException e) {
            return false;
        }
        String tipo = evento.isEmpty() ? Json.texto(no.path("type")) : evento;
        switch (tipo) {
            case "message_start" -> {
                JsonNode uso = no.path("message").path("usage");
                leitura.entrada = Json.numero(uso.path("input_tokens"));
                leitura.cacheEscrito = Json.numero(uso.path("cache_creation_input_tokens"));
                leitura.cacheLido = Json.numero(uso.path("cache_read_input_tokens"));
                leitura.saida = Json.numero(uso.path("output_tokens"));
            }
            case "content_block_delta" -> {
                JsonNode delta = no.path("delta");
                if ("text_delta".equals(Json.texto(delta.path("type")))) {
                    String texto = Json.texto(delta.path("text"));
                    if (!texto.isEmpty()) {
                        leitura.texto.append(texto);
                        aoTexto.accept(texto);
                    }
                }
            }
            case "message_delta" -> {
                String motivo = Json.texto(no.path("delta").path("stop_reason"));
                if (!motivo.isEmpty()) leitura.motivo = motivo;
                JsonNode uso = no.path("usage");
                if (uso.has("output_tokens")) leitura.saida = Json.numero(uso.path("output_tokens"));
            }
            case "message_stop" -> {
                leitura.terminou = true;
                return true;
            }
            case "error" -> {
                JsonNode erro = no.path("error");
                String detalhe = Json.texto(erro.path("type"));
                // Sobrecarga no meio do stream é o 529 que chegou tarde.
                throw new ErroDaApiDoModelo("overloaded_error".equals(detalhe) ? 529 : 500,
                        detalhe.isEmpty() ? "erro no meio do stream" : detalhe);
            }
            default -> { }
        }
        return false;
    }

    /** A resposta no formato de uma chamada sem stream. */
    ObjectNode remontar(Leitura leitura) {
        ObjectNode raiz = json.createObjectNode();
        raiz.putArray("content").addObject().put("type", "text").put("text", leitura.texto.toString());
        raiz.put("stop_reason", leitura.motivo);
        raiz.putObject("usage")
                .put("input_tokens", leitura.entrada)
                .put("output_tokens", leitura.saida)
                .put("cache_creation_input_tokens", leitura.cacheEscrito)
                .put("cache_read_input_tokens", leitura.cacheLido);
        return raiz;
    }
}
