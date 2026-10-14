package dev.jarvis.voz;

import java.time.Instant;

/**
 * O que a ElevenLabs respondeu sobre a assinatura.
 *
 * @param usados   caracteres já gastos no período
 * @param limite   caracteres do período
 * @param renovaEm quando a contagem zera; null quando a API não informa (plano sem renovação)
 */
public record SaldoDeVoz(long usados, long limite, Instant renovaEm) {

    /** Saldo desconhecido: é o estado antes da primeira consulta dar certo. */
    public static SaldoDeVoz desconhecido() {
        return new SaldoDeVoz(0, 0, null);
    }

    public boolean conhecido() {
        return limite > 0;
    }

    /** Quanto ainda dá para gastar, nunca negativo. */
    public long restante() {
        return Math.max(0, limite - usados);
    }

    /** Percentual já consumido, arredondado. Zero quando o limite é desconhecido. */
    public int percentualUsado() {
        return limite <= 0 ? 0 : (int) Math.round(usados * 100.0 / limite);
    }
}
