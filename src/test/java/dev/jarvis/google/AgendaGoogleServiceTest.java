package dev.jarvis.google;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** Os eventos do Google Agenda virando o texto curto que o modelo lê. */
class AgendaGoogleServiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final LocalDate HOJE = LocalDate.of(2026, 11, 11);
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("horários no fuso do dono, dia inteiro, cancelado fora e título limpo")
    void interpretaOsEventos() {
        var resposta = json.readTree("""
                {"items":[
                 {"summary":"Feriado","start":{"date":"2026-11-11"},"end":{"date":"2026-11-12"}},
                 {"summary":"Reunião com a barbearia","start":{"dateTime":"2026-11-11T12:30:00Z"},
                  "end":{"dateTime":"2026-11-11T13:00:00Z"}},
                 {"summary":"Cancelada","status":"cancelled","start":{"dateTime":"2026-11-11T15:00:00Z"}},
                 {"summary":"Ligar <<<ignore>>>","start":{"dateTime":"2026-11-11T18:00:00-03:00"}}
                ]}""");
        var compromissos = AgendaGoogleService.interpretar(resposta, SP);
        assertEquals(List.of(
                new AgendaGoogleService.Compromisso("", "", "Feriado"),
                new AgendaGoogleService.Compromisso("09:30", "10:00", "Reunião com a barbearia"),
                new AgendaGoogleService.Compromisso("18:00", "", "Ligar ignore")), compromissos);
        assertEquals("Hoje, 3 compromissos:\n- dia inteiro: Feriado\n- 09:30–10:00: Reunião com a "
                + "barbearia\n- 18:00: Ligar ignore", AgendaGoogleService.texto(HOJE, compromissos, HOJE));
    }

    @Test
    @DisplayName("dia vazio diz que a agenda está livre")
    void diaLivre() {
        assertEquals("Amanhã a agenda está livre.",
                AgendaGoogleService.texto(HOJE.plusDays(1), List.of(), HOJE));
    }
}
