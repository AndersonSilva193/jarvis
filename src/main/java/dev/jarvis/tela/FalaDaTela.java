package dev.jarvis.tela;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * As frases que o agente do PC pode fazer a tela falar — <b>só estas</b>.
 *
 * O agente posta uma chave, nunca um texto. O {@code PC_TOKEN} vive num arquivo
 * {@code .properties} numa máquina de mesa: aceitar texto livre ali transformaria esse token num
 * megafone para dizer qualquer coisa na tela da sala. Com catálogo fechado, o pior que um token
 * vazado consegue é repetir uma das frases abaixo.
 *
 * O conjunto cresce aqui, no servidor, e não exige tocar no agente.
 */
public final class FalaDaTela {

    private static final Map<String, String> FRASES = criar();

    private FalaDaTela() {
    }

    private static Map<String, String> criar() {
        Map<String, String> frases = new LinkedHashMap<>();
        frases.put("ja_estou_aqui", "Já estou aqui, senhor.");
        frases.put("modo_repouso", "Entrando em modo de repouso, senhor.");
        return Map.copyOf(frases);
    }

    /** As chaves aceitas, para a mensagem de erro dizer quais são. */
    public static Set<String> chaves() {
        return FRASES.keySet();
    }

    public static boolean conhece(String chave) {
        return chave != null && FRASES.containsKey(chave);
    }

    /** O texto da chave, ou nulo se ela não existe. */
    public static String texto(String chave) {
        return chave == null ? null : FRASES.get(chave);
    }
}
