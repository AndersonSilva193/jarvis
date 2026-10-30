package dev.jarvis.holograma.controle;

import java.security.SecureRandom;
import java.util.Base64;

/** Os segredos curtos do canal: 32 bytes sorteados, em base64url sem preenchimento (43 caracteres). */
final class Segredos {

    private static final SecureRandom SORTEIO = new SecureRandom();

    private Segredos() {
    }

    static String novo() {
        byte[] bytes = new byte[32];
        SORTEIO.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** O formato de {@link #novo()}: barra o lixo antes de ele virar chave de mapa ou linha de log. */
    static boolean formatoValido(String valor) {
        return valor != null && valor.matches("[A-Za-z0-9_-]{43}");
    }
}
