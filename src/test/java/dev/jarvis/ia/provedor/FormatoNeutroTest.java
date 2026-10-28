package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** O histórico como vai ao disco: legível por qualquer provedor, e sem perder o da Anthropic. */
class FormatoNeutroTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final FormatoNeutro formato = new FormatoNeutro(JSON);
    private final ConversorAnthropic conversor = new ConversorAnthropic(JSON);

    @Test
    @DisplayName("o turno compacto é só texto, nas chaves neutras")
    void turnoCompactoEhTexto() {
        ObjectNode salvo = formato.paraJson(Mensagem.texto(Mensagem.Papel.ASSISTENTE,
                "Você tem notas.md.\n[ferramentas: listar_arquivos]"));

        assertEquals("{\"papel\":\"assistente\",\"blocos\":[{\"tipo\":\"texto\","
                + "\"texto\":\"Você tem notas.md.\\n[ferramentas: listar_arquivos]\"}]}", salvo.toString());
    }

    @Test
    @DisplayName("um turno completo vai e volta igual, e a Anthropic recebe o mesmo JSON de antes")
    void turnoCompletoIdaEVolta() {
        ArrayNode doModelo = JSON.createArrayNode();
        doModelo.addObject().put("type", "server_tool_use").put("id", "srvtoolu_1")
                .put("name", "web_search").putObject("input").put("query", "SSD 1TB");
        doModelo.addObject().put("type", "web_search_tool_result").put("tool_use_id", "srvtoolu_1")
                .putArray("content").addObject().put("encrypted_content", "EqgfCioIARgBIiQ3");
        doModelo.addObject().put("type", "text").put("text", "Vou listar.");
        doModelo.addObject().put("type", "tool_use").put("id", "toolu_1").put("name", "listar_arquivos")
                .set("input", JSON.createObjectNode());
        List<Mensagem> turno = List.of(
                Mensagem.texto(Mensagem.Papel.USUARIO, "lista meus arquivos"),
                new Mensagem(Mensagem.Papel.ASSISTENTE, conversor.blocosDe(doModelo)),
                new Mensagem(Mensagem.Papel.USUARIO,
                        List.of(new Bloco.Resultado("toolu_1", "{\"arquivos\":[]}", false))));

        List<Mensagem> relido = turno.stream()
                .map(m -> formato.deJson(JSON.readTree(formato.paraJson(m).toString()))).toList();

        assertEquals(turno, relido);
        assertEquals(doModelo.toString(), conversor.mensagem(relido.get(1)).path("content").toString(),
                "encrypted_content alterado é HTTP 400");
        assertEquals("SSD 1TB", ((Bloco.Opaco) relido.get(1).blocos().getFirst()).consultaWeb());
    }

    @Test
    @DisplayName("o arquivo gravado antes do formato neutro ainda é lido")
    void formatoAntigoAindaELido() {
        ObjectNode antigo = JSON.createObjectNode().put("role", "user");
        antigo.putArray("content").addObject().put("type", "tool_result").put("tool_use_id", "tu_1")
                .put("content", "{\"arquivos\":[]}").put("is_error", true);

        Mensagem lida = formato.deJson(antigo);

        assertEquals(new Mensagem(Mensagem.Papel.USUARIO,
                List.of(new Bloco.Resultado("tu_1", "{\"arquivos\":[]}", true))), lida);
        assertEquals(Mensagem.texto(Mensagem.Papel.ASSISTENTE, "De nada."), formato.deJson(
                JSON.createObjectNode().put("role", "assistant").put("content", "De nada.")));
    }

    @Test
    @DisplayName("papel desconhecido estoura, e mensagem sem bloco vira a marcação de sempre")
    void formatoTorto() {
        assertThrows(IllegalArgumentException.class,
                () -> formato.deJson(JSON.createObjectNode().put("papel", "sistema")));
        Mensagem vazia = formato.deJson(JSON.createObjectNode().put("papel", "usuario"));
        assertEquals("(sem resposta)", vazia.texto());
        ObjectNode semOriginal = formato.paraJson(Mensagem.texto(Mensagem.Papel.USUARIO, "oi"));
        assertFalse(semOriginal.toString().contains("original"));
        assertTrue(formato.paraJson(new Mensagem(Mensagem.Papel.ASSISTENTE, List.of(
                new Bloco.Opaco(ConversorAnthropic.PROVEDOR, JSON.createObjectNode(), true, ""))))
                .toString().contains("\"soNoTurno\":true"));
    }
}
