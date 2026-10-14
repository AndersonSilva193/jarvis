package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneId;
import java.time.ZonedDateTime;

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
        return PromptDeSistema.montar(JSON, PERSONALIDADE, cachear, canal, "Anderson", AGORA);
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
        assertTrue(dinamico.contains("não existir ferramenta"), dinamico);
    }

    @Test
    @DisplayName("cada canal manda a sua regra de formato")
    void regrasPorCanal() {
        assertTrue(texto(montar(Canal.TELEGRAM, true).get(1)).contains("negrito"));

        String hud = texto(montar(Canal.HUD, true).get(1));
        assertTrue(hud.contains("falado em voz alta"), hud);
        assertTrue(hud.contains("nada de markdown"), hud);

        String briefing = texto(montar(Canal.BRIEFING, true).get(1));
        assertTrue(briefing.contains("não altere nenhum número"), briefing);

        String tarefa = texto(montar(Canal.TAREFA, true).get(1));
        assertTrue(tarefa.contains("linha de status"), tarefa);
    }

    @Test
    @DisplayName("a regra antiga de 'texto simples, sem Markdown' saiu do Telegram")
    void telegramAceitaFormatacao() {
        String telegram = texto(montar(Canal.TELEGRAM, true).get(1));

        assertFalse(telegram.contains("nada de markdown"), telegram);
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
