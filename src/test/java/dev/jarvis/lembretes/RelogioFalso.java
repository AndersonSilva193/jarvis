package dev.jarvis.lembretes;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/**
 * Um relógio que só anda quando o teste manda, como o {@code RelogioFalso} dos testes do PC.
 *
 * Existe porque os casos interessantes do {@link LembreteService} são justamente os do tempo:
 * lembrete que venceu durante a parada e série que precisa pular ocorrências perdidas. Nenhum
 * deles pode custar horas de {@code sleep}.
 */
class RelogioFalso extends Clock {

    private Instant agora;

    RelogioFalso(String instante) {
        this.agora = Instant.parse(instante);
    }

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

    void avancar(Duration quanto) {
        agora = agora.plus(quanto);
    }
}
