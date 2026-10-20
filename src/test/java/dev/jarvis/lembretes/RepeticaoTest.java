package dev.jarvis.lembretes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O cálculo da próxima ocorrência, que é o coração da recorrência. */
class RepeticaoTest {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    private static ZonedDateTime em(String local) {
        return LocalDateTime.parse(local).atZone(ZONA);
    }

    private static String proxima(Repeticao repeticao, String desde) {
        return repeticao.proxima(em(desde)).orElseThrow().toLocalDateTime().toString();
    }

    @Test
    @DisplayName("lembrete sem recorrência não tem próxima ocorrência")
    void semRecorrencia() {
        assertTrue(Repeticao.NENHUMA.proxima(em("2026-10-19T08:00")).isEmpty());
        assertFalse(Repeticao.NENHUMA.recorrente());
        assertFalse(Repeticao.NENHUMA.ocorreEm(LocalDate.of(2026, 10, 19)));
    }

    @Test
    @DisplayName("diário avança um dia e mantém o horário")
    void diario() {
        assertEquals("2026-10-20T08:30", proxima(Repeticao.diario(), "2026-10-19T08:30"));
    }

    @Test
    @DisplayName("dias úteis numa sexta pula o fim de semana e cai na segunda")
    void diasUteisNaSexta() {
        // 2026-10-16 é uma sexta-feira.
        assertEquals(DayOfWeek.FRIDAY, LocalDate.of(2026, 10, 16).getDayOfWeek());
        assertEquals("2026-10-19T07:00", proxima(Repeticao.diasUteis(), "2026-10-16T07:00"));
    }

    @Test
    @DisplayName("dias úteis numa quarta vai para a quinta, e o fim de semana não tem ocorrência")
    void diasUteisNoMeioDaSemana() {
        assertEquals("2026-10-15T07:00", proxima(Repeticao.diasUteis(), "2026-10-14T07:00"));
        assertFalse(Repeticao.diasUteis().ocorreEm(LocalDate.of(2026, 10, 17)));  // sábado
        assertTrue(Repeticao.diasUteis().ocorreEm(LocalDate.of(2026, 10, 19)));   // segunda
    }

    @Test
    @DisplayName("semanal vai ao próximo dia da lista, dando a volta na semana")
    void semanal() {
        Repeticao segundaEQuarta = Repeticao.semanal(EnumSet.of(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY));
        // Segunda 19 -> quarta 21 -> segunda 26.
        assertEquals("2026-10-21T19:00", proxima(segundaEQuarta, "2026-10-19T19:00"));
        assertEquals("2026-10-26T19:00", proxima(segundaEQuarta, "2026-10-21T19:00"));
        assertTrue(segundaEQuarta.ocorreEm(LocalDate.of(2026, 10, 21)));
        assertFalse(segundaEQuarta.ocorreEm(LocalDate.of(2026, 10, 20)));
    }

    @Test
    @DisplayName("semanal sem nenhum dia é recusada na construção")
    void semanalSemDias() {
        assertThrows(IllegalArgumentException.class, () -> Repeticao.semanal(Set.of()));
    }

    @Test
    @DisplayName("mensal dia 31 cai no último dia de fevereiro e volta ao 31 no mês seguinte")
    void mensalDia31() {
        Repeticao todoDia31 = Repeticao.mensal(31);
        assertEquals("2026-02-28T09:00", proxima(todoDia31, "2026-01-31T09:00"));
        // O pedido original sobrevive ao mês curto: de fevereiro ele volta para o 31 de março.
        assertEquals("2026-03-31T09:00", proxima(todoDia31, "2026-02-28T09:00"));
        assertTrue(todoDia31.ocorreEm(LocalDate.of(2026, 2, 28)));
        assertTrue(todoDia31.ocorreEm(LocalDate.of(2026, 3, 31)));
        assertFalse(todoDia31.ocorreEm(LocalDate.of(2026, 3, 30)));
    }

    @Test
    @DisplayName("mensal dia 29 pega o 29 de fevereiro em ano bissexto")
    void mensalEmAnoBissexto() {
        assertEquals("2028-02-29T09:00", proxima(Repeticao.mensal(29), "2028-01-29T09:00"));
    }

    @Test
    @DisplayName("a virada de ano é só mais um dia, em todos os tipos")
    void viradaDeAno() {
        assertEquals("2027-01-01T06:00", proxima(Repeticao.diario(), "2026-12-31T06:00"));
        assertEquals("2027-01-10T06:00", proxima(Repeticao.mensal(10), "2026-12-10T06:00"));
        // 31/12/2026 é uma quinta; o próximo dia útil é a sexta 01/01.
        assertEquals("2027-01-01T06:00", proxima(Repeticao.diasUteis(), "2026-12-31T06:00"));
        assertEquals("2027-01-04T06:00",
                proxima(Repeticao.semanal(EnumSet.of(DayOfWeek.MONDAY)), "2026-12-28T06:00"));
    }

    @Test
    @DisplayName("mensal fora de 1..31 é recusada")
    void mensalInvalido() {
        assertThrows(IllegalArgumentException.class, () -> Repeticao.mensal(0));
        assertThrows(IllegalArgumentException.class, () -> Repeticao.mensal(32));
    }
}
