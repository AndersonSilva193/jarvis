package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import dev.jarvis.web.BriefingDto;

class SaudacaoTest {
    private ZonedDateTime horario(String hora) {
        return ZonedDateTime.parse("2026-10-14T" + hora + ":00-03:00[America/Sao_Paulo]");
    }

    @ParameterizedTest
    @CsvSource({"00:00,Boa noite", "04:59,Boa noite", "05:00,Bom dia", "11:59,Bom dia",
            "12:00,Boa tarde", "17:59,Boa tarde", "18:00,Boa noite", "23:59,Boa noite"})
    void cumprimentoEFallbackUsamMesmoPeriodo(String hora, String esperado) {
        var briefing = new BriefingService.Briefing(horario(hora), "Anderson", List.of());
        assertEquals(esperado, Saudacao.cumprimento(horario(hora)));
        assertTrue(briefing.abertura().startsWith(esperado));
        assertTrue(briefing.paraTelegram().startsWith(briefing.abertura()));
        assertTrue(briefing.paraFala().startsWith(briefing.abertura()));
        assertEquals("America/Sao_Paulo", BriefingDto.de(briefing, true).fusoHorario());
    }

    @Test void cacheNaoAtravessaPeriodoDataOuInstanteFuturo() {
        Duration minuto = Duration.ofMinutes(1);
        assertFalse(Saudacao.reutilizavel(horario("11:59").plusSeconds(40), horario("12:00"), minuto));
        assertFalse(Saudacao.reutilizavel(horario("17:59").plusSeconds(40), horario("18:00"), minuto));
        assertFalse(Saudacao.reutilizavel(horario("23:59").plusSeconds(40), horario("00:00").plusDays(1), minuto));
        assertFalse(Saudacao.reutilizavel(horario("19:00"), horario("18:00"), minuto));
        assertTrue(Saudacao.reutilizavel(horario("18:00"), horario("18:00").plusSeconds(30).withZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo")), minuto));
    }
}
