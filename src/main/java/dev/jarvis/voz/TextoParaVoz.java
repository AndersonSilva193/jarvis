package dev.jarvis.voz;

/**
 * Tira o markdown antes de mandar o texto para a ElevenLabs.
 *
 * Duas razões: um asterisco lido em voz alta estraga a frase, e cada caractere enviado custa
 * crédito — não faz sentido pagar por marcação que ninguém ouve.
 */
public final class TextoParaVoz {

    private TextoParaVoz() {
    }

    public static String limpar(String texto) {
        if (texto == null || texto.isBlank()) {
            return "";
        }
        String limpo = texto;
        // Links: fica o rótulo, some a URL (que seria lida caractere a caractere).
        limpo = limpo.replaceAll("\\[([^\\]]*)\\]\\([^)]*\\)", "$1");
        // Código: o conteúdo é falado, as crases não.
        limpo = limpo.replaceAll("```[a-zA-Z]*", " ").replace("`", "");
        // Marcadores de título, de lista e de citação no começo da linha.
        limpo = limpo.replaceAll("(?m)^\\s{0,3}#{1,6}\\s*", "");
        limpo = limpo.replaceAll("(?m)^\\s*[-*+•]\\s+", "");
        limpo = limpo.replaceAll("(?m)^\\s*>\\s?", "");
        // Negrito, itálico e tachado. O sublinhado só cai entre palavras, para não comer o nome
        // de uma variável que o Jarvis às vezes cita.
        limpo = limpo.replaceAll("\\*{1,3}([^*]+)\\*{1,3}", "$1");
        limpo = limpo.replaceAll("(?<![\\w_])_{1,3}([^_]+)_{1,3}(?![\\w_])", "$1");
        limpo = limpo.replaceAll("~~([^~]+)~~", "$1");
        // Sobras de espaço depois de tudo isso.
        limpo = limpo.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n");
        return limpo.trim();
    }
}
