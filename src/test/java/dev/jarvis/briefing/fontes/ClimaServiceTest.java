package dev.jarvis.briefing.fontes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa só a leitura da resposta da Open-Meteo, com um JSON de exemplo guardado em
 * src/test/resources: sem Spring e sem rede, então roda rápido e não depende da API estar no ar.
 */
class ClimaServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode exemplo() throws IOException {
        try (InputStream in = ClimaServiceTest.class.getResourceAsStream("/clima-open-meteo.json")) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("lê todos os campos do clima a partir da resposta da API")
    void interpretaRespostaCompleta() throws Exception {
        ClimaHoje clima = ClimaService.interpretar(exemplo(), "Curitiba");

        assertEquals("Curitiba", clima.cidade());
        assertEquals(14.9, clima.temperaturaAtual(), 0.001);
        assertEquals(13.6, clima.minima(), 0.001);
        assertEquals(22.5, clima.maxima(), 0.001);
        assertEquals(88, clima.chanceChuvaPct());
        assertEquals(8.3, clima.ventoKmh(), 0.001);
        assertEquals("chuva fraca", clima.descricao());
    }

    @Test
    @DisplayName("traduz os códigos WMO conhecidos e avisa quando não conhece")
    void traduzCodigosDoTempo() {
        assertEquals("céu limpo", ClimaService.descrever(0));
        assertEquals("nublado", ClimaService.descrever(3));
        assertEquals("trovoada", ClimaService.descrever(95));
        assertTrue(ClimaService.descrever(123).contains("123"),
                "código desconhecido deve aparecer na descrição");
    }

    @Test
    @DisplayName("reclama quando falta um campo em vez de inventar zero")
    void falhaQuandoFaltaCampo() throws Exception {
        var raiz = (tools.jackson.databind.node.ObjectNode) exemplo();
        ((tools.jackson.databind.node.ObjectNode) raiz.path("current")).remove("temperature_2m");

        IOException erro = assertThrows(IOException.class, () -> ClimaService.interpretar(raiz, "Curitiba"));
        assertTrue(erro.getMessage().contains("current.temperature_2m"), erro.getMessage());
    }
}
