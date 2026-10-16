package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jarvis.conversa.EscutaResposta;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.Canal;
import tools.jackson.databind.json.JsonMapper;

class OuvirRespostaFerramentaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final OuvirRespostaFerramenta ferramenta = new OuvirRespostaFerramenta(json);

    @Test void marcaEscutaSemComandarATela() {
        var entrada = json.createObjectNode().put("tempo", 12).put("motivo", "confirmar o horário");
        entrada.putArray("opcoes").add("sim").add("não");
        var resultado = ferramenta.executarDetalhado(entrada, new OuvinteProgresso() { });
        assertEquals(new EscutaResposta(12, List.of("sim", "não"), "confirmar o horário"), resultado.escuta());
        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.comandos().isEmpty());
        assertTrue(resultado.artefatos().isEmpty());
        assertNull(resultado.confirmacao());
    }

    @Test void aplicaPadraoELimites() {
        var entrada = json.createObjectNode().put("motivo", "confirmar");
        assertEquals(8, ferramenta.executarDetalhado(entrada, null).escuta().tempoSegundos());
        assertTrue(ferramenta.executarDetalhado(entrada, null).escuta().opcoes().isEmpty());
        entrada.put("tempo", -1);
        assertEquals(5, ferramenta.executarDetalhado(entrada, null).escuta().tempoSegundos());
        entrada.put("tempo", Long.MAX_VALUE);
        assertEquals(20, ferramenta.executarDetalhado(entrada, null).escuta().tempoSegundos());
    }

    @Test void opcoesSaoImutaveis() {
        var opcoes = new ArrayList<>(List.of("sim"));
        var escuta = new EscutaResposta(8, opcoes, "confirmar");
        opcoes.clear();
        assertEquals(List.of("sim"), escuta.opcoes());
        assertThrows(UnsupportedOperationException.class, () -> escuta.opcoes().add("não"));
        assertTrue(new EscutaResposta(8, null, "confirmar").opcoes().isEmpty());
    }

    @Test void naoOfereceNoTelegramEPreservaListaSemCanal() {
        var registro = new Ferramentas(List.of(ferramenta), json);
        assertTrue(registro.definicoes(Canal.TELEGRAM).isEmpty());
        assertEquals("ouvir_resposta", registro.definicoes(Canal.HUD).get(0).path("name").stringValue());
        assertEquals(registro.definicoes(), registro.definicoes(Canal.HUD));
    }

    @Test void omiteProgressoVazioEPreservaODasOutrasFerramentas() {
        Ferramenta comum = new Ferramenta() {
            public String nome() { return "clima"; }
            public String descricao() { return "Clima"; }
            public tools.jackson.databind.node.ObjectNode esquemaEntrada() { return json.createObjectNode(); }
            public String executar(tools.jackson.databind.JsonNode entrada) { return "sol"; }
        };
        var eventos = new ArrayList<Object>();
        var registro = new Ferramentas(List.of(ferramenta, comum), json, eventos::add);
        var avisos = new ArrayList<String>();
        var resultados = new ArrayList<ResultadoFerramenta>();
        var ouvinte = new OuvinteProgresso() {
            @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                avisos.add(descricao);
            }
        };
        var chamada = json.createObjectNode().put("id", "1").put("name", "ouvir_resposta");
        chamada.putObject("input").put("motivo", "confirmar");
        registro.executar(chamada, ouvinte, resultados::add);
        assertTrue(avisos.isEmpty());
        assertTrue(eventos.isEmpty());
        assertNotNull(resultados.getFirst().escuta());
        chamada.put("name", "clima");
        registro.executar(chamada, ouvinte, resultados::add);
        assertEquals(List.of("consultando o clima"), avisos);
    }
}
