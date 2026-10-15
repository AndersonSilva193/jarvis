package dev.jarvis.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Comparação de segredo em tempo constante — o único lugar do projeto que toca
 * {@code MessageDigest}.
 *
 * Usado pelos filtros de token da HUD e do PC e pelo código de confirmação das ações perigosas.
 * Fica em {@code util} porque esses três chamadores moram em pacotes diferentes, e ter duas
 * cópias de comparação de segredo é como nasce a versão errada.
 */
public final class ComparadorDeToken {

    private ComparadorDeToken() {
    }

    /**
     * Verdadeiro quando os dois valores são iguais, gastando o mesmo tempo em qualquer caso.
     *
     * <p>Comparamos o SHA-256 dos dois, e não os textos: o {@code MessageDigest.isEqual} só é
     * constante para arrays do mesmo tamanho — com o hash, o tamanho do segredo nunca influencia
     * o tempo. Vazio ou nulo em qualquer lado é sempre falso, para "não configurado" fechar a
     * porta em vez de abri-la.
     */
    public static boolean igual(String fornecido, String esperado) {
        if (fornecido == null || fornecido.isBlank() || esperado == null || esperado.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(fornecido), sha256(esperado));
    }

    private static byte[] sha256(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 deveria existir em qualquer JVM", e);
        }
    }
}
