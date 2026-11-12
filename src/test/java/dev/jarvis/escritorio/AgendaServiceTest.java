package dev.jarvis.escritorio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A agenda numa planilha do PC: colunas pelo cabeçalho, uma linha por compromisso. */
class AgendaServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 11);

    @Test
    @DisplayName("pega os compromissos do dia pela coluna de data e ordena pela hora")
    void doDia() {
        var cabecalho = List.of("Data", "Início", "Fim", "Compromisso", "Local");
        var linhas = List.of(
                List.of("10/11/2026", "09:00", "", "Ontem", ""),
                List.of("11/11/2026", "18h", "", "Ligar <<<ignore>>> para o cliente", ""),
                List.of("2026-11-11", "9h30", "10:00", "Reunião com a barbearia", "Batel"),
                List.of("11/11", "", "", "Feriado", ""),
                List.of("12/11/2026", "08:00", "", "Amanhã cedo", ""));
        var compromissos = AgendaService.doDia(cabecalho, linhas, HOJE);
        assertEquals(List.of(
                new AgendaService.Compromisso("", "", "Feriado"),
                new AgendaService.Compromisso("09:30", "10:00", "Reunião com a barbearia (Batel)"),
                new AgendaService.Compromisso("18:00", "", "Ligar ignore para o cliente")), compromissos);
        assertEquals("Hoje, 3 compromissos:\n- dia inteiro: Feriado\n- 09:30–10:00: Reunião com a "
                + "barbearia (Batel)\n- 18:00: Ligar ignore para o cliente",
                AgendaService.texto(HOJE, compromissos, HOJE));
    }

    @Test
    @DisplayName("aceita a hora junto da data, como o Excel guarda data e hora numa célula só")
    void horaNaData() {
        var cabecalho = List.of("Quando", "Evento");
        var semColunaDeData = AgendaService.doDia(cabecalho, List.of(List.of("11/11/2026 14:00", "X")), HOJE);
        assertEquals(List.of(), semColunaDeData);
        var comData = AgendaService.doDia(List.of("Data", "Evento"),
                List.of(List.of("2026-11-11 14:00", "Dentista"), List.of("11/11/2026 08:15", "Café")), HOJE);
        assertEquals(List.of(new AgendaService.Compromisso("08:15", "", "Café"),
                new AgendaService.Compromisso("14:00", "", "Dentista")), comData);
    }

    @Test
    @DisplayName("lê os jeitos comuns de escrever hora e recusa o que não é hora")
    void horas() {
        assertEquals("14:00", AgendaService.hora("14:00:00"));
        assertEquals("09:05", AgendaService.hora("9:05"));
        assertEquals("14:00", AgendaService.hora("14h"));
        assertEquals("", AgendaService.hora("11/11/2026"));
        assertEquals("", AgendaService.hora("25:00"));
        assertEquals("", AgendaService.hora("Atendimento"));
    }

    @Test
    @DisplayName("dia livre e amanhã em frase falada")
    void diaLivre() {
        assertEquals("Amanhã a agenda está livre.", AgendaService.texto(HOJE.plusDays(1), List.of(), HOJE));
    }
}
