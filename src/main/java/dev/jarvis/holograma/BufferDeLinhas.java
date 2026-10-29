package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.List;

/**
 * Junta os pedaços de texto que o modelo transmite e devolve as linhas que ficaram completas.
 *
 * <p>Um delta não respeita linha: pode trazer meia peça, três peças ou o fim de uma e o começo da
 * seguinte. Só a linha fechada por {@code \n} vai ao parser; o que sobra espera o próximo delta, e
 * o {@link #resto()} entrega o último pedaço quando o stream acaba sem quebra final. O {@code \r}
 * de um {@code \r\n} sai junto.
 */
final class BufferDeLinhas {

    private final StringBuilder pendente = new StringBuilder();

    List<String> alimentar(String delta) {
        if (delta == null || delta.isEmpty()) {
            return List.of();
        }
        pendente.append(delta);
        List<String> linhas = new ArrayList<>();
        int inicio = 0;
        for (int i = 0; i < pendente.length(); i++) {
            if (pendente.charAt(i) == '\n') {
                linhas.add(semRetorno(pendente.substring(inicio, i)));
                inicio = i + 1;
            }
        }
        pendente.delete(0, inicio);
        return linhas;
    }

    /** O que ficou sem quebra de linha; esvazia o buffer. */
    String resto() {
        String resto = semRetorno(pendente.toString());
        pendente.setLength(0);
        return resto;
    }

    private static String semRetorno(String linha) {
        return linha.endsWith("\r") ? linha.substring(0, linha.length() - 1) : linha;
    }
}
