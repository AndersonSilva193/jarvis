package dev.jarvis.briefing.fontes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import dev.jarvis.briefing.fontes.ClimaService.PrevisaoDia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa só a leitura da resposta da Open-Meteo, com um JSON de exemplo guardado em
 * src/test/resources: sem Spring e sem rede, então roda rápido e não depende da API estar no ar.
 */
class ClimaServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode exemplo() throws IOException {
        return arquivo("/clima-open-meteo.json");
    }

    private static JsonNode arquivo(String caminho) throws IOException {
        try (InputStream in = ClimaServiceTest.class.getResourceAsStream(caminho)) {
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

    @Test
    @DisplayName("previsão de um dia futuro: lê os campos do bloco daily")
    void previsaoDeUmDia() throws Exception {
        LocalDate sabado = LocalDate.of(2026, 10, 17);
        PrevisaoDia p = ClimaService.interpretarPrevisao(
                arquivo("/clima-previsao-open-meteo.json"), "Curitiba", sabado);

        assertEquals(sabado, p.data());
        assertEquals("Curitiba", p.cidade());
        assertEquals(12.1, p.minima(), 0.001);
        assertEquals(21.5, p.maxima(), 0.001);
        assertEquals(77, p.chanceChuvaPct());
        assertEquals(11.7, p.ventoMaxKmh(), 0.001);
        assertEquals("névoa", p.descricao());
        assertTrue(p.completa());
    }

    @Test
    @DisplayName("no limite do horizonte, campo nulo vira null em vez de estourar ou virar zero")
    void previsaoIncompletaNaoEstoura() throws Exception {
        LocalDate longe = LocalDate.of(2026, 10, 28);
        PrevisaoDia p = assertDoesNotThrow(() -> ClimaService.interpretarPrevisao(
                arquivo("/clima-previsao-incompleta.json"), "Curitiba", longe));

        assertNull(p.minima());
        assertNull(p.maxima());
        assertNull(p.ventoMaxKmh());
        assertEquals("", p.descricao());
        assertFalse(p.completa());
        // A chance de chuva é o único dado que a API ainda dá nessa distância.
        assertEquals(60, p.chanceChuvaPct());
    }

    @Test
    @DisplayName("resposta sem o bloco daily é erro, não previsão vazia")
    void previsaoSemDaily() throws Exception {
        LocalDate dia = LocalDate.of(2026, 10, 17);
        var semDaily = (tools.jackson.databind.node.ObjectNode) arquivo("/clima-previsao-open-meteo.json");
        semDaily.remove("daily");

        IOException erro = assertThrows(IOException.class,
                () -> ClimaService.interpretarPrevisao(semDaily, "Curitiba", dia));
        assertTrue(erro.getMessage().contains("daily"), erro.getMessage());
    }

    @Test
    @DisplayName("lê os sete dias do intervalo, cada um com a data que a própria API informou")
    void interpretaIntervaloDeDias() throws Exception {
        var dias = ClimaService.interpretarPrevisoes(arquivo("/clima-previsao-7-dias.json"), "Curitiba");

        assertEquals(7, dias.size());
        // A data sai do daily.time, não de uma contagem a partir de hoje: rótulo deslocado em um
        // dia desenharia um gráfico errado sem parecer errado.
        assertEquals(LocalDate.of(2026, 10, 23), dias.getFirst().data());
        assertEquals(LocalDate.of(2026, 10, 29), dias.getLast().data());
        assertEquals("Curitiba", dias.getFirst().cidade());
        assertEquals(21.5, dias.getFirst().maxima(), 0.001);
        assertEquals(12.1, dias.getFirst().minima(), 0.001);
        assertEquals(10, dias.getFirst().chanceChuvaPct());
        assertEquals("nublado", dias.getFirst().descricao());
        assertEquals(28.0, dias.get(4).maxima(), 0.001);
        assertEquals("trovoada", dias.getLast().descricao());
        for (PrevisaoDia dia : dias) {
            assertTrue(dia.completa(), "faltou temperatura em " + dia.data());
        }
    }

    @Test
    @DisplayName("intervalo sem o bloco daily é erro, igual ao caminho de um dia")
    void intervaloSemDaily() throws Exception {
        var semDaily = (tools.jackson.databind.node.ObjectNode) arquivo("/clima-previsao-7-dias.json");
        semDaily.remove("daily");

        IOException erro = assertThrows(IOException.class,
                () -> ClimaService.interpretarPrevisoes(semDaily, "Curitiba"));
        assertTrue(erro.getMessage().contains("daily"), erro.getMessage());
    }

    @Test
    @DisplayName("janela aceita hoje e 15 dias à frente, recusa ontem e datas distantes")
    void validaJanelaDePrevisao() {
        LocalDate hoje = LocalDate.of(2026, 10, 13);

        assertDoesNotThrow(() -> ClimaService.validarJanela(hoje, hoje));
        assertDoesNotThrow(() -> ClimaService.validarJanela(hoje.plusDays(15), hoje));

        var passado = assertThrows(IllegalArgumentException.class,
                () -> ClimaService.validarJanela(hoje.minusDays(1), hoje));
        assertTrue(passado.getMessage().contains("já passou"), passado.getMessage());

        var longe = assertThrows(IllegalArgumentException.class,
                () -> ClimaService.validarJanela(hoje.plusDays(16), hoje));
        assertTrue(longe.getMessage().contains("28/10"), longe.getMessage());
    }
}
