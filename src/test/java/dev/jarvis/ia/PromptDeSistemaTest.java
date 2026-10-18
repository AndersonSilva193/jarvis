package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Testa o que vai no campo "system" da chamada ao Claude. Sem Spring e sem rede: a montagem é uma
 * função pura justamente para dar para conferir o JSON aqui.
 */
class PromptDeSistemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZonedDateTime AGORA =
            ZonedDateTime.of(2026, 10, 13, 8, 30, 0, 0, ZoneId.of("America/Sao_Paulo"));
    private static final String PERSONALIDADE = "# Jarvis\n\nTrate o Anderson por senhor.";

    private static ArrayNode montar(Canal canal, boolean cachear) {
        return montar(canal, cachear, false);
    }

    private static ArrayNode montar(Canal canal, boolean cachear, boolean buscaAtiva) {
        return montar(canal, cachear, buscaAtiva, List.of());
    }

    private static ArrayNode montar(Canal canal, boolean cachear, boolean buscaAtiva,
            List<String> naTela) {
        return PromptDeSistema.montar(JSON, PERSONALIDADE, cachear, canal, "Anderson", AGORA,
                buscaAtiva, naTela);
    }

    private static String texto(JsonNode bloco) {
        return bloco.path("text").stringValue();
    }

    @Test
    @DisplayName("o bloco fixo da personalidade vem antes do dinâmico")
    void fixoAntesDoDinamico() {
        ArrayNode system = montar(Canal.TELEGRAM, true);

        assertEquals(2, system.size());
        assertEquals("text", system.get(0).path("type").stringValue());
        assertEquals(PERSONALIDADE, texto(system.get(0)),
                "o bloco fixo tem que ser a personalidade, byte a byte, ou o cache nunca casa");
        assertTrue(texto(system.get(1)).contains("Agora são"), texto(system.get(1)));
    }

    @Test
    @DisplayName("cache_control só no bloco fixo, e só quando ele é grande o bastante")
    void cacheControl() {
        ArrayNode comCache = montar(Canal.TELEGRAM, true);
        assertEquals("ephemeral", comCache.get(0).path("cache_control").path("type").stringValue());
        assertTrue(comCache.get(1).path("cache_control").isMissingNode(),
                "o bloco dinâmico muda a cada pedido: cachear ele só pagaria a escrita à toa");

        ArrayNode semCache = montar(Canal.TELEGRAM, false);
        assertTrue(semCache.get(0).path("cache_control").isMissingNode(),
                "abaixo do mínimo do modelo o marcador seria ignorado calado; melhor nem mandar");
        assertTrue(semCache.get(1).path("cache_control").isMissingNode());
    }

    @Test
    @DisplayName("o bloco dinâmico traz data, fuso, canal e as regras técnicas")
    void blocoDinamico() {
        String dinamico = texto(montar(Canal.TELEGRAM, true).get(1));

        assertTrue(dinamico.contains("13/10/2026 08:30"), dinamico);
        assertTrue(dinamico.contains("America/Sao_Paulo"), dinamico);
        assertTrue(dinamico.contains("canal: telegram"), dinamico);
        assertTrue(dinamico.contains("nunca invente o resultado"), dinamico);
        assertTrue(dinamico.contains("use tarefa_no_pc para pedir"), dinamico);
        assertTrue(dinamico.contains("Não diga que falta"), dinamico);
        assertTrue(dinamico.contains("não limita tarefa_no_pc"), dinamico);
    }

    @Test
    @DisplayName("cada canal manda a sua regra de formato")
    void regrasPorCanal() {
        assertTrue(texto(montar(Canal.TELEGRAM, true).get(1)).contains("negrito"));

        String hud = texto(montar(Canal.HUD, true).get(1));
        assertTrue(hud.contains("falado em voz alta"), hud);
        assertTrue(hud.contains("nada de markdown"), hud);
        assertTrue(hud.contains("Tela limpa."), hud);
        assertTrue(hud.contains("não leia em voz alta o que ele"), hud);

        String briefing = texto(montar(Canal.BRIEFING, true).get(1));
        assertTrue(briefing.contains("não altere nenhum número"), briefing);
        assertFalse(briefing.contains("use tarefa_no_pc"), briefing);

        String tarefa = texto(montar(Canal.TAREFA, true).get(1));
        assertTrue(tarefa.contains("linha de status"), tarefa);
        assertFalse(tarefa.contains("use tarefa_no_pc"), tarefa);
        assertTrue(hud.contains("use tarefa_no_pc para pedir"), hud);
    }

    @Test
    @DisplayName("a regra antiga de 'texto simples, sem Markdown' saiu do Telegram")
    void telegramAceitaFormatacao() {
        String telegram = texto(montar(Canal.TELEGRAM, true).get(1));

        assertFalse(telegram.contains("nada de markdown"), telegram);
    }

    @Test
    @DisplayName("o que está na tela entra só no bloco dinâmico, nunca no fixo")
    void estadoDaTelaSoNoDinamico() {
        ArrayNode system = montar(Canal.HUD, true, false, List.of(
                "[c7k2] vídeo 'Lo-fi para estudar' (tocando)",
                "[cf09] pesquisa 'Eleição 2026' (fixada)"));

        assertEquals(PERSONALIDADE, texto(system.get(0)),
                "o bloco fixo é o cacheado: um caractere a mais aqui e o cache de prompt se perde");
        assertFalse(texto(system.get(0)).contains("Na tela agora"), texto(system.get(0)));

        String dinamico = texto(system.get(1));
        assertTrue(dinamico.contains("Na tela agora"), dinamico);
        assertTrue(dinamico.contains("[c7k2] vídeo 'Lo-fi para estudar' (tocando)"), dinamico);
        assertTrue(dinamico.contains("[cf09] pesquisa 'Eleição 2026' (fixada)"), dinamico);
        assertTrue(dinamico.contains("controlar_tela"), dinamico);
    }

    @Test
    @DisplayName("sem HUD conectada, nada de tela aparece em bloco nenhum")
    void semTelaNaoAparece() {
        ArrayNode system = montar(Canal.TELEGRAM, true, false, List.of());

        assertFalse(texto(system.get(0)).contains("Na tela agora"));
        assertFalse(texto(system.get(1)).contains("Na tela agora"),
                "a ausência destas linhas é o sinal, para o modelo, de que não há tela para abrir nada");
    }

    @Test
    @DisplayName("o estado da tela vale em qualquer canal: pelo Telegram também se manda card")
    void telaValeNoTelegram() {
        String dinamico = texto(montar(Canal.TELEGRAM, true, false,
                List.of("[c1] música 'Jazz' (tocando)")).get(1));

        assertTrue(dinamico.contains("[c1] música 'Jazz' (tocando)"), dinamico);
    }

    @Test
    @DisplayName("os nomes dos canais são os combinados com a HUD e o briefing")
    void nomesDosCanais() {
        assertEquals("telegram", Canal.TELEGRAM.nome());
        assertEquals("hud", Canal.HUD.nome());
        assertEquals("briefing", Canal.BRIEFING.nome());
        assertEquals("tarefa", Canal.TAREFA.nome());
    }
}
