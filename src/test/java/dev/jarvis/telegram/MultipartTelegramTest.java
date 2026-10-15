package dev.jarvis.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O corpo multipart do sendPhoto, conferido sem rede. */
class MultipartTelegramTest {

    private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0x42 };

    private static byte[] juntar(List<byte[]> partes) {
        ByteArrayOutputStream saida = new ByteArrayOutputStream();
        partes.forEach(parte -> saida.write(parte, 0, parte.length));
        return saida.toByteArray();
    }

    private static Map<String, String> campos() {
        Map<String, String> campos = new LinkedHashMap<>();
        campos.put("chat_id", "42");
        campos.put("caption", "Print do PC");
        return campos;
    }

    @Test
    @DisplayName("o corpo abre, separa e fecha com o boundary")
    void estruturaDoCorpo() {
        String boundary = MultipartTelegram.boundary();
        String corpo = new String(juntar(MultipartTelegram.corpo(boundary, campos(), "photo",
                "tela.png", "image/png", PNG)), StandardCharsets.ISO_8859_1);

        assertTrue(corpo.startsWith("--" + boundary + "\r\n"), "o corpo abre no boundary");
        assertTrue(corpo.endsWith("--" + boundary + "--\r\n"), "e fecha com o boundary final");
        assertEquals(3, corpo.split("Content-Disposition", -1).length - 1,
                "dois campos de texto e o arquivo = três partes");
        assertTrue(corpo.contains("name=\"chat_id\"\r\n\r\n42\r\n"));
        assertTrue(corpo.contains("name=\"caption\"\r\n\r\nPrint do PC\r\n"));
        assertTrue(corpo.contains("name=\"photo\"; filename=\"tela.png\"\r\nContent-Type: image/png"));
    }

    @Test
    @DisplayName("os bytes da imagem passam intactos")
    void bytesIntactos() {
        byte[] corpo = juntar(MultipartTelegram.corpo(MultipartTelegram.boundary(), campos(),
                "photo", "tela.png", "image/png", PNG));

        int achados = 0;
        for (int i = 0; i + PNG.length <= corpo.length; i++) {
            boolean casa = true;
            for (int j = 0; j < PNG.length && casa; j++) {
                casa = corpo[i + j] == PNG[j];
            }
            if (casa) {
                achados++;
            }
        }
        assertEquals(1, achados, "a imagem tem de aparecer uma vez, byte a byte");
    }

    @Test
    @DisplayName("o nome do arquivo é limpo de aspa e quebra de linha")
    void nomeDeArquivoLimpo() {
        String boundary = MultipartTelegram.boundary();
        String corpo = new String(juntar(MultipartTelegram.corpo(boundary, campos(), "photo",
                "tela\".png\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n99",
                "image/png", PNG)), StandardCharsets.ISO_8859_1);

        // Uma aspa ali encerraria a parte e deixaria injetar outra — trocando o chat_id, por
        // exemplo, e mandando o print do PC para outra conversa.
        assertFalse(corpo.contains("tela\".png"), "a aspa tinha que sair do nome");
        assertEquals(1, corpo.split("name=\"chat_id\"", -1).length - 1,
                "o nome do arquivo não pode criar um segundo chat_id");
    }

    @Test
    @DisplayName("campo nulo não entra no corpo")
    void campoNuloForaDoCorpo() {
        Map<String, String> semLegenda = new LinkedHashMap<>();
        semLegenda.put("chat_id", "42");
        semLegenda.put("caption", null);

        String corpo = new String(juntar(MultipartTelegram.corpo(MultipartTelegram.boundary(),
                semLegenda, "photo", "tela.png", "image/png", PNG)), StandardCharsets.ISO_8859_1);

        assertFalse(corpo.contains("caption"), "sem legenda, o campo nem é enviado");
    }
}
