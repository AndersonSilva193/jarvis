package dev.jarvis.custo;

import java.util.Locale;

/**
 * Dólar escrito como se fala em português.
 *
 * A vírgula decimal não é preciosismo: a resposta do {@code custo_do_jarvis} é lida em voz alta na
 * HUD, e "US$ 1.42" sai da síntese como "um ponto quarenta e dois". E valor abaixo de um centavo
 * vira "menos de US$ 0,01" em vez de "US$ 0,00", que soaria como "de graça".
 */
public final class Dinheiro {

    private static final Locale BR = Locale.of("pt", "BR");

    private Dinheiro() {
    }

    public static String usd(double valor) {
        if (valor > 0 && valor < 0.005) {
            return "menos de US$ 0,01";
        }
        return "US$ " + numero(valor);
    }

    /** Só o número, para listas onde "US$" já foi dito uma vez. */
    public static String numero(double valor) {
        return String.format(BR, "%.2f", valor);
    }
}
