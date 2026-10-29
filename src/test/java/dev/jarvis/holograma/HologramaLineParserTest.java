package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/** Uma linha por vez, nunca lançando: o lixo que modelo pequeno escreve volta vazio. */
class HologramaLineParserTest {

    private final HologramaLineParser parser = new HologramaLineParser(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = { "", "   ", "```", "```json", "``` ndjson", "Aqui está o objeto:",
            "<think>vou começar pelo bloco</think>", "[", "]", "{\"t\":\"p\",\"id\":\"bloco\",\"f\":\"bo",
            "{\"t\":\"p\" \"id\":\"x\"}", "[1,2,3]", "42", "\"texto\"", "{}", "{\"t\":\"desenho\"}",
            "{\"t\":7}", "{'t':'p'}", "data: {\"t\":\"fim\"}" })
    @DisplayName("linha vazia, cerca, texto solto, JSON quebrado ou de tipo desconhecido volta vazia")
    void ignora(String linha) {
        assertTrue(parser.ler(linha).isEmpty(), linha);
    }

    @Test
    @DisplayName("null não estoura")
    void nulo() {
        assertTrue(parser.ler(null).isEmpty());
    }

    @Test
    @DisplayName("a meta sai normalizada: espaços colapsados, controle tirado, textos cortados")
    void meta() {
        var evento = parser.ler("  {\"t\":\"meta\",\"nome\":\"  Motor\\tV8 \",\"cat\":\"mecanica\",\"desc\":\""
                + "x".repeat(300) + "\"}  ");
        var meta = assertInstanceOf(EventoHolograma.Meta.class, evento.orElseThrow()).meta();
        assertEquals("Motor V8", meta.nome());
        assertEquals("mecanica", meta.categoria());
        assertEquals(HologramaMeta.MAX_DESCRICAO, meta.descricao().length());
    }

    @Test
    @DisplayName("meta sem nome recebe o nome padrão; campos ausentes viram texto vazio")
    void metaSemNome() {
        var meta = assertInstanceOf(EventoHolograma.Meta.class, parser.ler("{\"t\":\"meta\"}").orElseThrow()).meta();
        assertEquals(HologramaMeta.PADRAO.nome(), meta.nome());
        assertEquals("", meta.categoria());
        assertEquals("", meta.descricao());
    }

    @Test
    @DisplayName("a peça sai compacta, como o modelo escreveu, para o validador conferir")
    void peca() {
        var evento = parser.ler("{\"t\":\"p\",\"id\":\"bloco\",\"f\":\"box\",\"p\":[0,0,0]}");
        var peca = assertInstanceOf(EventoHolograma.Peca.class, evento.orElseThrow());
        assertEquals("bloco", peca.compacta().get("id").stringValue());
    }

    @Test
    @DisplayName("vírgula no fim e tipo em maiúsculas são tolerados")
    void tolerancias() {
        assertInstanceOf(EventoHolograma.Peca.class, parser.ler("{\"t\":\"p\",\"id\":\"a\"},").orElseThrow());
        assertInstanceOf(EventoHolograma.Fim.class, parser.ler("{\"t\":\" FIM \"}").orElseThrow());
    }
}
