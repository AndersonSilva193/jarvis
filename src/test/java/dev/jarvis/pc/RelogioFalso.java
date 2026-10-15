package dev.jarvis.pc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Um relógio que só anda quando o teste manda.
 *
 * Existe para os testes de validade e de "offline" não custarem minuto de {@code sleep}: a fila e
 * o guarda de confirmação recebem o {@code Clock} pelo construtor exatamente para isso.
 */
public class RelogioFalso extends Clock {

    private Instant agora = Instant.parse("2026-10-15T12:00:00Z");

    @Override
    public Instant instant() {
        return agora;
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zona) {
        return this;
    }

    public void avancar(Duration quanto) {
        agora = agora.plus(quanto);
    }
}
