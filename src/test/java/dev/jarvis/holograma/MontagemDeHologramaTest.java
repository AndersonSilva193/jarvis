package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

/** A ordem do que sai, e os três exemplos de referência passando sem um aviso sequer. */
class MontagemDeHologramaTest {

    private final HologramaLineParser parser = new HologramaLineParser(JsonMapper.builder().build());

    private List<SaidaHolograma> montar(List<String> linhas) {
        MontagemDeHolograma montagem = new MontagemDeHolograma(parser);
        List<SaidaHolograma> saidas = new ArrayList<>();
        linhas.forEach(linha -> saidas.addAll(montagem.processar(linha)));
        saidas.addAll(montagem.terminar());
        return saidas;
    }

    @Test
    @DisplayName("resposta com cerca, texto e linha cortada ainda monta o que estava bom")
    void respostaSuja() {
        List<SaidaHolograma> saidas = montar(List.of("```json",
                "{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"Um cubo\"}",
                "Agora as peças:",
                "{\"t\":\"p\",\"id\":\"cubo\",\"f\":\"box\"}",
                "{\"t\":\"p\",\"id\":\"tampa\",\"f\":\"bo",
                "```"));
        assertEquals("Cubo", assertInstanceOf(SaidaHolograma.Meta.class, saidas.getFirst()).meta().nome());
        assertEquals("cubo", assertInstanceOf(SaidaHolograma.Peca.class, saidas.get(1)).peca().id());
        // Sem a linha de fim, o terminar() avisa e fecha.
        assertInstanceOf(SaidaHolograma.Aviso.class, saidas.get(2));
        assertEquals(new SaidaHolograma.Fim(1, 1), saidas.getLast());
    }

    @Test
    @DisplayName("peça antes da meta: sai a meta padrão primeiro, com aviso")
    void semMeta() {
        List<SaidaHolograma> saidas = montar(List.of("{\"t\":\"p\",\"id\":\"a\"}", "{\"t\":\"fim\"}"));
        assertEquals(HologramaMeta.PADRAO, assertInstanceOf(SaidaHolograma.Meta.class, saidas.get(0)).meta());
        assertInstanceOf(SaidaHolograma.Aviso.class, saidas.get(1));
        assertInstanceOf(SaidaHolograma.Peca.class, saidas.get(2));
        assertEquals(new SaidaHolograma.Fim(1, 1), saidas.getLast());
    }

    @Test
    @DisplayName("meta repetida é ignorada, e nada sai depois do fim — nem o terminar()")
    void depoisDoFim() {
        List<SaidaHolograma> saidas = montar(List.of("{\"t\":\"meta\",\"nome\":\"A\"}",
                "{\"t\":\"meta\",\"nome\":\"B\"}", "{\"t\":\"fim\"}", "{\"t\":\"p\",\"id\":\"tarde\"}"));
        assertEquals(3, saidas.size(), saidas.toString());
        assertEquals("A", assertInstanceOf(SaidaHolograma.Meta.class, saidas.get(0)).meta().nome());
        assertInstanceOf(SaidaHolograma.Aviso.class, saidas.get(1));
        assertEquals(new SaidaHolograma.Fim(0, 1), saidas.get(2));
    }

    @ParameterizedTest
    @CsvSource({ "motor_v8, 150", "dna, 100", "atomo, 20" })
    @DisplayName("os exemplos de referência montam sem aviso, com ids únicos e dentro do limite")
    void exemplos(String nome, int minimoDePecas) {
        List<SaidaHolograma> saidas = montar(new ExemplosDeHolograma().linhas(nome).orElseThrow());
        List<String> avisos = saidas.stream().filter(SaidaHolograma.Aviso.class::isInstance)
                .map(s -> ((SaidaHolograma.Aviso) s).texto()).toList();
        assertEquals(List.of(), avisos);

        List<HologramaPeca> pecas = saidas.stream().filter(SaidaHolograma.Peca.class::isInstance)
                .map(s -> ((SaidaHolograma.Peca) s).peca()).toList();
        assertTrue(pecas.size() >= minimoDePecas, nome + ": " + pecas.size());
        assertTrue(pecas.size() <= HologramaValidator.MAX_PECAS);
        assertEquals(pecas.size(), new HashSet<>(pecas.stream().map(HologramaPeca::id).toList()).size());
        assertInstanceOf(SaidaHolograma.Meta.class, saidas.getFirst());
        assertEquals(new SaidaHolograma.Fim(pecas.size(), 0), saidas.getLast());
    }

    @Test
    @DisplayName("nome de exemplo com ponto ou barra nem vira caminho")
    void nomeDeExemplo() {
        ExemplosDeHolograma exemplos = new ExemplosDeHolograma();
        assertTrue(exemplos.linhas("../application").isEmpty());
        assertTrue(exemplos.linhas("motor_v8.ndjson").isEmpty());
        assertTrue(exemplos.linhas("nao_existe").isEmpty());
        assertTrue(exemplos.linhas(null).isEmpty());
    }
}
