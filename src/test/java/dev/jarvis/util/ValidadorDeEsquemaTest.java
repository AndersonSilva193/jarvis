package dev.jarvis.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.CriarLembreteFerramenta;
import dev.jarvis.lembretes.LembreteService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** O subconjunto de JSON Schema que os esquemas do Jarvis usam, e só ele. */
class ValidadorDeEsquemaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode ler(String texto) {
        return JSON.readTree(texto);
    }

    private static String problema(String valor, String esquema) {
        return ValidadorDeEsquema.problema(ler(valor), ler(esquema)).orElse(null);
    }

    @Test
    @DisplayName("tipo, com 2.0 contando como inteiro e 2.5 não")
    void tipo() {
        assertEquals(null, problema("2.0", "{\"type\":\"integer\"}"));
        assertEquals("a entrada deveria ser um número inteiro", problema("2.5", "{\"type\":\"integer\"}"));
        assertEquals("a entrada deveria ser um texto", problema("3", "{\"type\":\"string\"}"));
        assertEquals("a entrada deveria ser um objeto", problema("[]", "{\"type\":\"object\"}"));
        assertEquals(null, problema("true", "{\"type\":\"boolean\"}"));
    }

    @Test
    @DisplayName("required e properties, com o nome do campo na frase")
    void objeto() {
        String esquema = """
                {"type":"object","required":["acao"],
                 "properties":{"acao":{"type":"string","enum":["subir","baixar"]},
                               "nivel":{"type":"integer","minimum":0,"maximum":100}}}""";
        assertEquals("falta o campo 'acao'", problema("{}", esquema));
        assertEquals("falta o campo 'acao'", problema("{\"acao\":null}", esquema));
        assertTrue(problema("{\"acao\":\"pular\"}", esquema).startsWith("o campo 'acao' tem de ser um destes"));
        assertEquals("o campo 'nivel' tem de ser no máximo 100", problema("{\"acao\":\"subir\",\"nivel\":101}", esquema));
        assertEquals(null, problema("{\"acao\":\"subir\",\"nivel\":100.0}", esquema));
        assertEquals(null, problema("{\"acao\":\"subir\",\"extra\":1}", esquema), "campo a mais não reprova");
    }

    @Test
    @DisplayName("texto: tamanho em caracteres, e pattern procurado em qualquer ponto")
    void texto() {
        assertEquals("a entrada passou de 3 caracteres", problema("\"abcd\"", "{\"maxLength\":3}"));
        assertEquals(null, problema("\"ação\"", "{\"maxLength\":4}"));
        assertEquals("a entrada precisa de ao menos 2 caractere(s)", problema("\"a\"", "{\"minLength\":2}"));
        assertEquals(null, problema("\"id 0a-1\"", "{\"pattern\":\"[0-9a-f]-1\"}"));
        assertEquals("a entrada está fora do formato esperado", problema("\"zz\"", "{\"pattern\":\"^[0-9]+$\"}"));
        assertEquals(null, problema("\"zz\"", "{\"pattern\":\"([\"}"), "padrão ilegível não reprova");
    }

    @Test
    @DisplayName("lista: tamanho e cada item contra items")
    void lista() {
        String esquema = "{\"type\":\"array\",\"minItems\":1,\"maxItems\":2,\"items\":{\"type\":\"string\"}}";
        assertEquals("a entrada precisa de ao menos 1 item(ns)", problema("[]", esquema));
        assertEquals("a entrada passou de 2 itens", problema("[\"a\",\"b\",\"c\"]", esquema));
        assertEquals("a entrada[1] deveria ser um texto", problema("[\"a\",2]", esquema));
    }

    @Test
    @DisplayName("const e número comparados pelo valor")
    void constante() {
        assertEquals(null, problema("1.0", "{\"const\":1}"));
        assertEquals(null, problema("1", "{\"enum\":[1.0, 2]}"));
        assertEquals("a entrada deveria ser \"editar\"", problema("\"apagar\"", "{\"const\":\"editar\"}"));
    }

    @Test
    @DisplayName("o esquemaEntrada() de uma ferramenta de verdade")
    void ferramentaReal() {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", null, null, null, null,
                null, null, null, null, null, null, null);
        JsonNode esquema = new CriarLembreteFerramenta(mock(LembreteService.class), JSON, props)
                .esquemaEntrada();

        ObjectNode bom = JSON.createObjectNode().put("texto", "pagar a luz")
                .put("data_hora", "2026-10-28T09:00").put("recorrencia", "nenhuma");
        assertTrue(ValidadorDeEsquema.problema(bom, esquema).isEmpty());
        assertEquals("falta o campo 'data_hora'", ValidadorDeEsquema.problema(
                JSON.createObjectNode().put("texto", "x"), esquema).orElseThrow());
        ObjectNode dias = bom.deepCopy();
        dias.put("recorrencia", "semanal").putArray("dias_da_semana").add("domingo").add("feriado");
        assertTrue(ValidadorDeEsquema.problema(dias, esquema).orElseThrow()
                .startsWith("o campo 'dias_da_semana'[1] tem de ser um destes"));
    }
}
