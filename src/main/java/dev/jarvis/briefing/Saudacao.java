package dev.jarvis.briefing;

import java.time.Duration;
import java.time.ZonedDateTime;

/** O período pertence ao instante e ao fuso do briefing, nunca ao relógio do navegador. */
public final class Saudacao {
    private Saudacao() { }

    public static String cumprimento(ZonedDateTime instante) {
        int hora = instante.getHour();
        return hora >= 5 && hora < 12 ? "Bom dia" : hora >= 12 && hora < 18 ? "Boa tarde" : "Boa noite";
    }

    public static String abertura(ZonedDateTime instante) {
        return cumprimento(instante) + ", senhor. Seu briefing está pronto.";
    }

    public static boolean reutilizavel(ZonedDateTime gerado, ZonedDateTime agora, Duration validade) {
        ZonedDateTime local = agora.withZoneSameInstant(gerado.getZone());
        Duration idade = Duration.between(gerado, local);
        return !idade.isNegative() && idade.compareTo(validade) < 0
                && gerado.toLocalDate().equals(local.toLocalDate())
                && cumprimento(gerado).equals(cumprimento(local));
    }
}
