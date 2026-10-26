package dev.jarvis.proativo;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A faixa de horas em que o Jarvis não fala sem ser chamado, no formato {@code "23:00-07:00"}.
 *
 * <p><b>Cruza a meia-noite de propósito</b>, e é por isso que não é um par de {@code LocalTime}
 * comparado com {@code isAfter}/{@code isBefore}: a janela útil é justamente a da noite, em que o
 * fim é numericamente <i>menor</i> que o início. Com a comparação ingênua, {@code 23:00-07:00}
 * jamais conteria hora alguma e o silêncio nunca valeria — falha calada, do tipo que só aparece às
 * três da manhã.
 *
 * <p>A interpretação mora aqui, e não no {@code JarvisProperties}, pela mesma regra da
 * {@code TabelaDePrecos}: o record de configuração guarda o texto cru e quem o entende é um lugar
 * só.
 */
public record JanelaDeSilencio(LocalTime inicio, LocalTime fim) {

    private static final Logger log = LoggerFactory.getLogger(JanelaDeSilencio.class);

    /** O que vale quando ninguém configurou nada: a madrugada. */
    public static final JanelaDeSilencio PADRAO =
            new JanelaDeSilencio(LocalTime.of(23, 0), LocalTime.of(7, 0));

    /**
     * Lê {@code "23:00-07:00"}. Texto torto avisa em WARN e devolve o {@link #PADRAO}.
     *
     * <p>Não lança pela regra do {@code numeroDeCusto}: um typo no {@code .env} não pode impedir o
     * Jarvis de subir. E o padrão escolhido é o silêncio da madrugada, não "sem silêncio": em
     * dúvida, melhor atrasar um aviso do que acordar o dono.
     */
    public static JanelaDeSilencio de(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return PADRAO;
        }
        String[] partes = bruto.trim().split("-", 2);
        if (partes.length < 2) {
            log.warn("PROATIVO_SILENCIO: '{}' não tem o formato HH:MM-HH:MM; usando {}",
                    bruto.trim(), PADRAO);
            return PADRAO;
        }
        try {
            return new JanelaDeSilencio(LocalTime.parse(partes[0].trim()),
                    LocalTime.parse(partes[1].trim()));
        } catch (DateTimeParseException naoEhHora) {
            log.warn("PROATIVO_SILENCIO: '{}' não é um par de horas; usando {}", bruto.trim(),
                    PADRAO);
            return PADRAO;
        }
    }

    /** Janela de largura zero é "sem silêncio": o dono desligou o recurso escrevendo a mesma hora. */
    public boolean desligada() {
        return inicio.equals(fim);
    }

    /** Se este instante cai dentro da janela, no fuso dado. O início conta; o fim, não. */
    public boolean contem(Instant quando, ZoneId zona) {
        if (desligada()) {
            return false;
        }
        LocalTime hora = quando.atZone(zona).toLocalTime();
        if (inicio.isBefore(fim)) {
            return !hora.isBefore(inicio) && hora.isBefore(fim);
        }
        // Cruzando a meia-noite: dentro da janela é depois do início OU antes do fim.
        return !hora.isBefore(inicio) || hora.isBefore(fim);
    }

    @Override
    public String toString() {
        return inicio + "-" + fim;
    }
}
