package dev.jarvis.pc;

import java.time.Instant;

/**
 * O PC parou de falar com a VPS: passou de 90 s sem contato depois de ter estado online. Irmão do
 * {@link PcVoltou}, e pelo mesmo motivo publicado só pelo {@link PcService}. Sai uma vez por
 * ausência; o próximo contato rearma.
 *
 * @param ultimoContato o último sinal de vida antes do silêncio
 * @param percebidoEm   quando a VPS deu o PC como fora
 */
public record PcDesligou(Instant ultimoContato, Instant percebidoEm) {
}
