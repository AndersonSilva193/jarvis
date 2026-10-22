package dev.jarvis.util;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Comparação de texto escrito por gente: sem acento, sem maiúscula e sem espaço sobrando.
 *
 * Serve a lugares onde a grafia exata não pode decidir o resultado: a chave do cache de
 * buscas no YouTube ("Lo-Fi  Para Estudar" e "lo-fi para estudar" são a mesma busca), o
 * {@code abrir_noticia}, onde o modelo repete o título ditado em voz alta — e "eleicao 2026"
 * tem de achar "Eleição 2026" — e a <b>prova de citação</b> da memória, onde um trecho só é
 * aceito se constar do que o usuário escreveu ({@code FerramentaMemoria.exigirConteudoDoUsuario}
 * e o {@code AnotadorDeMemoria} usam esta mesma função, de propósito: duas normalizações
 * parecidas deixariam a trava valer uma coisa na ferramenta e outra no anotador).
 *
 * A pontuação é preservada — só o acento, a caixa e o espaço sobrando saem. Comparação de
 * citação não pode ignorar pontuação, senão o modelo poderia remontar a frase do usuário.
 */
public final class Texto {

    private static final Pattern MARCAS = Pattern.compile("\\p{Mn}+");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    private static final Locale BR = Locale.of("pt", "BR");

    private Texto() {
    }

    /** "Eleição" -> "Eleicao". A decomposição NFD separa a letra do acento; aqui o acento cai. */
    public static String semAcento(String bruto) {
        if (bruto == null || bruto.isEmpty()) {
            return "";
        }
        return MARCAS.matcher(Normalizer.normalize(bruto, Normalizer.Form.NFD)).replaceAll("");
    }

    /** A forma canônica de comparação: sem acento, em minúsculas e com os espaços colapsados. */
    public static String normalizar(String bruto) {
        return ESPACOS.matcher(semAcento(bruto).toLowerCase(BR)).replaceAll(" ").trim();
    }

    /**
     * Se o que foi procurado casa com o candidato. Igualdade primeiro e, falhando, o candidato
     * contendo o procurado — o modelo costuma dizer um pedaço do título, não o título inteiro.
     * Texto procurado vazio nunca casa, senão qualquer coisa casaria.
     */
    public static boolean casa(String procurado, String candidato) {
        String alvo = normalizar(procurado);
        String onde = normalizar(candidato);
        return !alvo.isEmpty() && !onde.isEmpty() && (onde.equals(alvo) || onde.contains(alvo));
    }
}
