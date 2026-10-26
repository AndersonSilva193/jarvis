package dev.jarvis.proativo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O catálogo fechado das frases proativas e a troca dos marcadores. */
class FalasServiceTest {

    private final FalasService falas = new FalasService();

    @Test
    @DisplayName("os marcadores são trocados pelos valores")
    void marcadoresSaoTrocados() {
        assertEquals("O site da loja está fora do ar desde 14h05 (HTTP 502).",
                falas.frase("site_fora", Map.of("site", "loja", "desde", "14h05",
                        "motivo", "HTTP 502")));
        assertEquals("O site da loja voltou. Ficou fora por 25 minutos.",
                falas.frase("site_voltou", Map.of("site", "loja", "duracao", "25 minutos")));
    }

    @Test
    @DisplayName("valor sobrando é ignorado: o mesmo mapa serve a duas frases")
    void valorSobrandoEhIgnorado() {
        Map<String, String> tudo = new HashMap<>();
        tudo.put("site", "api");
        tudo.put("duracao", "1 hora");
        tudo.put("motivo", "HTTP 500");
        tudo.put("desde", "03h00");
        assertEquals("O site da api voltou. Ficou fora por 1 hora.",
                falas.frase("site_voltou", tudo));
    }

    @Test
    @DisplayName("situação desconhecida estoura em vez de mandar frase errada")
    void situacaoDesconhecidaEstoura() {
        var erro = assertThrows(IllegalArgumentException.class,
                () -> falas.frase("site_pegou_fogo", Map.of()));
        assertTrue(erro.getMessage().contains("site_fora"), "a lista ajuda quem errou: "
                + erro.getMessage());
    }

    @Test
    @DisplayName("marcador sem valor estoura: {motivo} literal no Telegram é pior que teste vermelho")
    void marcadorSemValorEstoura() {
        var erro = assertThrows(IllegalArgumentException.class,
                () -> falas.frase("site_fora", Map.of("site", "loja")));
        assertTrue(erro.getMessage().contains("{desde}"), erro.getMessage());
    }

    @Test
    @DisplayName("a duração é falada no singular e no plural")
    void duracaoNoSingularENoPlural() {
        assertEquals("menos de um minuto", FalasService.duracaoFalada(Duration.ofSeconds(40)));
        assertEquals("1 minuto", FalasService.duracaoFalada(Duration.ofMinutes(1)));
        assertEquals("25 minutos", FalasService.duracaoFalada(Duration.ofMinutes(25)));
        assertEquals("1 hora", FalasService.duracaoFalada(Duration.ofHours(1)));
        assertEquals("2 horas", FalasService.duracaoFalada(Duration.ofHours(2)));
        assertEquals("2 horas e 5 minutos",
                FalasService.duracaoFalada(Duration.ofMinutes(125)));
        assertEquals("1 hora e 1 minuto", FalasService.duracaoFalada(Duration.ofMinutes(61)));
    }

    @Test
    @DisplayName("duração nula ou negativa não vira 'zero minutos'")
    void duracaoEstranhaNaoViraZero() {
        assertEquals("menos de um minuto", FalasService.duracaoFalada(null));
        assertEquals("menos de um minuto", FalasService.duracaoFalada(Duration.ofMinutes(-5)));
    }

    @Test
    @DisplayName("o valor do marcador entra literal, sem ser lido como grupo de regex")
    void valorComDolarNaoQuebra() {
        assertEquals("O site da lo$ja voltou. Ficou fora por 1 minuto.",
                falas.frase("site_voltou", Map.of("site", "lo$ja", "duracao", "1 minuto")));
    }
}
