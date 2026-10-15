package dev.jarvis.telegram;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Monta corpo {@code multipart/form-data} na mão.
 *
 * O {@code sendPhoto} é a única chamada da Bot API que não é JSON, e não vale trazer uma
 * dependência para três cabeçalhos e um boundary. Classe estática e sem rede de propósito: o
 * formato do corpo é testável sem fingir chamada HTTP.
 */
final class MultipartTelegram {

    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);

    private MultipartTelegram() {
    }

    static String boundary() {
        return "----jarvis" + UUID.randomUUID();
    }

    /**
     * As partes do corpo, prontas para o {@code BodyPublishers.ofByteArrays}.
     *
     * @param campos    os campos de texto (pares como {@code chat_id} e {@code caption}); valor
     *                  nulo é omitido
     * @param conteudo  os bytes do arquivo, que passam intactos
     */
    static List<byte[]> corpo(String boundary, Map<String, String> campos, String nomeDoCampo,
            String nomeDoArquivo, String mime, byte[] conteudo) {
        List<byte[]> partes = new ArrayList<>();
        for (Map.Entry<String, String> campo : campos.entrySet()) {
            if (campo.getValue() == null) {
                continue;
            }
            partes.add(texto("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + campo.getKey() + "\"\r\n\r\n"));
            partes.add(texto(campo.getValue()));
            partes.add(CRLF);
        }
        // O nome do arquivo vai dentro de um cabeçalho entre aspas: uma aspa ou uma quebra de
        // linha ali encerrariam a parte e deixariam injetar outra no meio do corpo.
        String seguro = nomeDoArquivo.replaceAll("[\"\\\\\\r\\n\\p{Cntrl}]", "_");
        partes.add(texto("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + nomeDoCampo
                + "\"; filename=\"" + seguro + "\"\r\nContent-Type: " + mime + "\r\n\r\n"));
        partes.add(conteudo);
        partes.add(CRLF);
        partes.add(texto("--" + boundary + "--\r\n"));
        return partes;
    }

    private static byte[] texto(String valor) {
        return valor.getBytes(StandardCharsets.UTF_8);
    }
}
