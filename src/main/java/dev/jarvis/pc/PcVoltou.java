package dev.jarvis.pc;

import java.time.Duration;
import java.time.Instant;

/**
 * O PC voltou a falar com a VPS depois de mais de 90 s calado — ou pela primeira vez desde a partida.
 * Evento de domínio, como o {@code ModoDeIaEvento}; quem publica é só o {@link PcService}, que é o
 * único que vê os dois caminhos de contato (o antigo e o E2).
 *
 * @param em       o instante do contato que encerrou a ausência
 * @param ausencia quanto tempo ficou sem contato, ou {@code null} se nunca houve contato antes
 */
public record PcVoltou(Instant em, Duration ausencia) {
}
