package dev.jarvis.gatilhos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A fonte {@link FonteDeGatilho#HORARIO}: um tique de 30 s confere as regras cujo {@code proximo}
 * venceu, avança cada uma pela {@code Repeticao} dos lembretes e dispara.
 *
 * <p>Avança <b>antes</b> de disparar, para um tique que caia no meio da gravação não disparar duas
 * vezes. Regra sem repetição é desligada depois do disparo: ela já fez o que tinha de fazer.
 *
 * <p>Atraso de até {@link #TOLERANCIA} (um restart, um tique perdido) ainda dispara. Mais que isso
 * pula para a próxima ocorrência: uma automação disparando horas depois do previsto pode fazer o
 * errado ("às 8h manda o resumo" chegando às 15h), diferente de um lembrete, que só avisa.
 */
@Component
public class RelogioDosGatilhos {

    private static final Logger log = LoggerFactory.getLogger(RelogioDosGatilhos.class);
    static final Duration TOLERANCIA = Duration.ofMinutes(15);

    private final Gatilhos gatilhos;
    private final MotorDeGatilhos motor;
    private final Clock relogio;

    @Autowired
    public RelogioDosGatilhos(Gatilhos gatilhos, MotorDeGatilhos motor) {
        this(gatilhos, motor, Clock.systemUTC());
    }

    public RelogioDosGatilhos(Gatilhos gatilhos, MotorDeGatilhos motor, Clock relogio) {
        this.gatilhos = gatilhos;
        this.motor = motor;
        this.relogio = relogio;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void tique() {
        try {
            conferir();
        } catch (RuntimeException e) {
            log.warn("Relógio dos gatilhos: {}", e.getMessage());
        }
    }

    void conferir() {
        Instant agora = relogio.instant();
        for (RegraDeGatilho regra : gatilhos.ativas(FonteDeGatilho.HORARIO)) {
            Instant previsto = regra.proximo();
            if (previsto == null || previsto.isAfter(agora)) continue;
            gatilhos.avancarHorario(regra.id(), seguinte(regra, previsto, agora).orElse(null));
            if (Duration.between(previsto, agora).compareTo(TOLERANCIA) > 0) {
                log.info("Gatilho {}: o horário {} passou há mais de {} min; fica para a próxima",
                        regra.id(), previsto, TOLERANCIA.toMinutes());
                continue;
            }
            motor.processar(EventoDeGatilho.de(FonteDeGatilho.HORARIO, agora, "regra", regra.id(),
                    "previsto", previsto.toString()));
        }
    }

    /** A primeira ocorrência depois de agora, no fuso do Jarvis; vazio quando não repete. */
    private Optional<Instant> seguinte(RegraDeGatilho regra, Instant previsto, Instant agora) {
        ZonedDateTime vez = previsto.atZone(gatilhos.zona());
        do {
            Optional<ZonedDateTime> outra = regra.filtro().repeticao().proxima(vez);
            if (outra.isEmpty()) return Optional.empty();
            vez = outra.get();
        } while (!vez.toInstant().isAfter(agora));
        return Optional.of(vez.toInstant());
    }
}
