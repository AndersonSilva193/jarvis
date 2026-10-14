package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.BriefingService.Briefing;
import dev.jarvis.briefing.BriefingService.Secao;
import dev.jarvis.lembretes.LembreteService.Lembrete;

/**
 * Testa a montagem do texto do briefing e o filtro de "lembretes de hoje", que é a parte com
 * lógica de data. Sem Spring e sem rede: o Briefing é montado à mão.
 */
class BriefingTest {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");
    private static final ZonedDateTime AGORA = ZonedDateTime.of(2026, 10, 13, 8, 0, 0, 0, ZONA);

    @Test
    @DisplayName("paraTexto() abre com a saudação e traz as seções na ordem")
    void textoCompleto() {
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "Curitiba: 18 °C agora (nublado)"),
                new Secao("lembretes de hoje", "09:00 Enviar o currículo"),
                new Secao("sistemas", "agendabarber-api: HTTP 200 em 180 ms (OK)")));

        String texto = briefing.paraTexto();

        assertEquals("""
                Bom dia, senhor. Seu briefing está pronto. terça-feira, 13/10 às 08:00

                CLIMA
                Curitiba: 18 °C agora (nublado)

                LEMBRETES DE HOJE
                09:00 Enviar o currículo

                SISTEMAS
                agendabarber-api: HTTP 200 em 180 ms (OK)""", texto);
    }

    @Test
    @DisplayName("seção que falhou aparece no texto em vez de desaparecer")
    void secaoComErroApareceNoTexto() {
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "não consegui consultar agora (Connection timed out)"),
                new Secao("notícias", "• [tecnologia] Manchete qualquer")));

        String texto = briefing.paraTexto();

        assertTrue(texto.contains("CLIMA\nnão consegui consultar agora (Connection timed out)"), texto);
        assertTrue(texto.contains("NOTÍCIAS"), texto);
    }

    @Test
    @DisplayName("os dados estruturados da HUD não vazam para o texto do Telegram")
    void dadosNaoEntramNoTexto() {
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "Curitiba: 18 °C agora (nublado)",
                        new DadosSecao.Clima("Curitiba", 18, 14, 22, 30, "nublado"))));

        assertEquals("""
                Bom dia, senhor. Seu briefing está pronto. terça-feira, 13/10 às 08:00

                CLIMA
                Curitiba: 18 °C agora (nublado)""", briefing.paraTexto());
    }

    @Test
    @DisplayName("sem nenhuma seção, ainda sai a saudação")
    void semSecoes() {
        String texto = new Briefing(AGORA, "chefe", List.of()).paraTexto();
        assertEquals("Bom dia, senhor. Seu briefing está pronto. terça-feira, 13/10 às 08:00", texto);
    }

    @Test
    @DisplayName("o cabeçalho acompanha o horário: tarde e noite não abrem com bom dia")
    void saudacaoSegueOHorario() {
        assertEquals("Boa tarde, senhor. Seu briefing está pronto. terça-feira, 13/10 às 15:30",
                new Briefing(AGORA.withHour(15).withMinute(30), "Anderson", List.of()).paraTexto());
        assertEquals("Boa noite, senhor. Seu briefing está pronto. terça-feira, 13/10 às 21:00",
                new Briefing(AGORA.withHour(21), "Anderson", List.of()).paraTexto());
    }

    @Test
    @DisplayName("lembretes de hoje: ignora os de outros dias, inclusive os que já passaram hoje")
    void filtraLembretesDeHoje() {
        List<Lembrete> pendentes = List.of(
                new Lembrete(1, "Reunião de ontem à noite", AGORA.minusHours(12)),
                new Lembrete(2, "Enviar o currículo", AGORA.plusHours(1)),
                new Lembrete(3, "Café da manhã (já passou hoje)", AGORA.minusHours(2)),
                new Lembrete(4, "Dentista semana que vem", AGORA.plusDays(7)));

        List<String> deHoje = BriefingService.lembretesDeHoje(pendentes, AGORA).stream()
                .map(Lembrete::texto)
                .toList();

        assertEquals(List.of("Enviar o currículo", "Café da manhã (já passou hoje)"), deHoje);
    }

    @Test
    @DisplayName("lembrete marcado em outro fuso é comparado pela data no fuso configurado")
    void lembreteEmOutroFuso() {
        // 03:00 de 14/10 em Londres é ainda 23:00 de 13/10 em São Paulo: conta como hoje.
        ZonedDateTime emLondres = ZonedDateTime.of(2026, 10, 14, 3, 0, 0, 0, ZoneId.of("Europe/London"));
        List<Lembrete> pendentes = List.of(new Lembrete(1, "Chamada internacional", emLondres));

        assertEquals(1, BriefingService.lembretesDeHoje(pendentes, AGORA).size());
    }

    @Test
    @DisplayName("sem narração: o Telegram leva os títulos em negrito e a fala não")
    void formatosSemNarracao() {
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "Curitiba: 18 °C agora (nublado)")));

        assertTrue(briefing.paraTelegram().contains("**CLIMA**"), briefing.paraTelegram());
        // Asterisco na fala viraria ruído na voz da HUD.
        assertFalse(briefing.paraFala().contains("**"), briefing.paraFala());
        assertFalse(briefing.paraTexto().contains("**"), briefing.paraTexto());
        assertEquals(briefing.paraTexto(), briefing.paraFala());
    }

    @Test
    @DisplayName("com narração: Telegram e HUD falam o mesmo texto do Jarvis")
    void formatosComNarracao() {
        String narrado = "Bom dia, senhor. Dezoito graus e nublado em Curitiba.";
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "Curitiba: 18 °C agora (nublado)")), narrado);

        assertEquals(narrado, briefing.paraTelegram());
        assertEquals(narrado, briefing.paraFala());
        // O texto montado em Java continua intacto: é ele que vai para o Claude narrar.
        assertTrue(briefing.paraTexto().contains("Curitiba: 18 °C agora (nublado)"));
    }

    @Test
    @DisplayName("narração em branco cai no texto montado em Java")
    void narracaoVaziaVoltaAoDeterministico() {
        Briefing semNada = new Briefing(AGORA, "Anderson", List.of(
                new Secao("clima", "Curitiba: 18 °C")), "   ");

        assertTrue(semNada.paraTelegram().contains("**CLIMA**"), semNada.paraTelegram());
        assertEquals(semNada.paraTexto(), semNada.paraFala());
    }
}
