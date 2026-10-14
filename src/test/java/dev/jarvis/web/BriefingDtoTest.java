package dev.jarvis.web;

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
import dev.jarvis.briefing.DadosSecao;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa o JSON que a HUD recebe: as chaves de `dados` em cada seção e o que acontece quando uma
 * seção falha. Sem Spring e sem rede — o Briefing é montado à mão e serializado com o JsonMapper
 * cru do projeto, que é o pior caso (nenhum módulo registrado).
 */
class BriefingDtoTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZonedDateTime AGORA =
            ZonedDateTime.of(2026, 10, 13, 8, 0, 0, 0, ZoneId.of("America/Sao_Paulo"));

    private static JsonNode comoJson(Secao... secoes) {
        Briefing briefing = new Briefing(AGORA, "Anderson", List.of(secoes));
        return JSON.readTree(JSON.writeValueAsString(BriefingDto.de(briefing, true)));
    }

    private static JsonNode dadosDaPrimeira(Secao secao) {
        return comoJson(secao).get("secoes").get(0).get("dados");
    }

    @Test
    @DisplayName("clima: a HUD recebe os números, não só a prosa")
    void dadosDoClima() {
        JsonNode dados = dadosDaPrimeira(new Secao("clima",
                "Curitiba: 18 °C agora (céu limpo), mínima 14 °C e máxima 22 °C",
                new DadosSecao.Clima("Curitiba", 18, 14, 22, 30, "céu limpo")));

        assertEquals("Curitiba", dados.get("cidade").stringValue());
        assertEquals(18, dados.get("temperaturaAtual").intValue());
        assertEquals(14, dados.get("minima").intValue());
        assertEquals(22, dados.get("maxima").intValue());
        assertEquals(30, dados.get("chanceChuvaPct").intValue());
        assertEquals("céu limpo", dados.get("descricao").stringValue());
    }

    @Test
    @DisplayName("notícias: imagem e link chegam à HUD (na prosa eles não cabem)")
    void dadosDasNoticias() {
        JsonNode dados = dadosDaPrimeira(new Secao("notícias", "• [tecnologia · G1] Manchete",
                new DadosSecao.Noticias(List.of(new DadosSecao.Noticias.Item(
                        "Manchete", "tecnologia", "G1",
                        "https://g1.globo.com/noticia", "https://g1.globo.com/foto.jpg")))));

        JsonNode item = dados.get("itens").get(0);
        assertEquals("Manchete", item.get("titulo").stringValue());
        assertEquals("tecnologia", item.get("tema").stringValue());
        assertEquals("G1", item.get("fonte").stringValue());
        assertEquals("https://g1.globo.com/noticia", item.get("link").stringValue());
        assertEquals("https://g1.globo.com/foto.jpg", item.get("imagemUrl").stringValue());
    }

    @Test
    @DisplayName("lembretes: hora já vem formatada em texto, sem depender de módulo de data")
    void dadosDosLembretes() {
        JsonNode dados = dadosDaPrimeira(new Secao("lembretes de hoje", "09:00 Enviar o currículo",
                new DadosSecao.Lembretes(List.of(
                        new DadosSecao.Lembretes.Item("09:00", "Enviar o currículo")))));

        assertEquals("09:00", dados.get("itens").get(0).get("hora").stringValue());
        assertEquals("Enviar o currículo", dados.get("itens").get(0).get("texto").stringValue());
    }

    @Test
    @DisplayName("sistemas: um item por site mais a linha do servidor")
    void dadosDosSistemas() {
        JsonNode dados = dadosDaPrimeira(new Secao("sistemas",
                "agendabarber-api: HTTP 200 em 180 ms (OK)\nServidor: CPU 12%",
                new DadosSecao.Sistemas(
                        List.of(new DadosSecao.Sistemas.Site("agendabarber-api", "HTTP 200 em 180 ms (OK)")),
                        "Servidor: CPU 12%")));

        assertEquals("agendabarber-api", dados.get("sites").get(0).get("nome").stringValue());
        assertEquals("HTTP 200 em 180 ms (OK)", dados.get("sites").get(0).get("resultado").stringValue());
        assertEquals("Servidor: CPU 12%", dados.get("servidor").stringValue());
    }

    @Test
    @DisplayName("seção sem dados estruturados vem com dados nulo e a explicação no conteúdo")
    void secaoQueFalhou() {
        JsonNode secao = comoJson(new Secao("clima", "não consegui consultar agora (timeout)"))
                .get("secoes").get(0);

        assertTrue(secao.get("dados").isNull(), secao.toString());
        assertEquals("não consegui consultar agora (timeout)", secao.get("conteudo").stringValue());
    }

    @Test
    @DisplayName("o resto do DTO não mudou: data ISO em texto, nome, texto da narração e geradoAgora")
    void camposDeSempre() {
        JsonNode json = comoJson(new Secao("clima", "Curitiba: 18 °C agora"));

        assertEquals("2026-10-13T08:00:00-03:00", json.get("geradoEm").stringValue());
        assertEquals("Anderson", json.get("nomeUsuario").stringValue());
        assertTrue(json.get("geradoAgora").booleanValue());
        assertEquals("America/Sao_Paulo", json.get("fusoHorario").stringValue());
        assertTrue(json.get("texto").stringValue().startsWith("Bom dia, senhor. Seu briefing está pronto."),
                json.get("texto").stringValue());
        assertFalse(json.get("texto").stringValue().contains("dados"));
    }

    @Test
    @DisplayName("encerramento: as observações do fim chegam em campo próprio, fora do texto")
    void encerramentoEmCampoProprio() {
        Briefing briefing = new Briefing(AGORA, "Anderson",
                List.of(new Secao("clima", "Curitiba: 18 °C agora", null, "Dezoito graus.")),
                "Bom dia, senhor.", "Nada que peça a sua atenção.",
                "Bom dia, senhor.\n\nDezoito graus.\n\nNada que peça a sua atenção.");

        JsonNode json = JSON.readTree(JSON.writeValueAsString(BriefingDto.de(briefing, true)));

        assertEquals("Bom dia, senhor.", json.get("abertura").stringValue());
        assertEquals("Nada que peça a sua atenção.", json.get("encerramento").stringValue());
    }

    @Test
    @DisplayName("sem narração o encerramento vem vazio: é como a HUD sabe que não há o que oferecer")
    void semNarracaoNaoTemEncerramento() {
        JsonNode json = comoJson(new Secao("clima", "Curitiba: 18 °C agora"));

        assertEquals("", json.get("encerramento").stringValue());
    }
}
