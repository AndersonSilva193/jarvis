package dev.jarvis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.PeriodoFinanceiro;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** O JSON-RPC do servidor MCP: descoberta, chamada e os erros na divisão que o protocolo pede. */
class ServidorMcpTest {

    @TempDir
    Path pasta;

    private final JsonMapper json = JsonMapper.builder().build();
    private final RelogioFalso relogio = new RelogioFalso("2026-11-11T15:00:00Z");

    private FinancasService financas;

    private ServidorMcp servidor() {
        financas = new FinancasService(json, relogio, ZoneId.of("America/Sao_Paulo"),
                pasta.resolve(FinancasService.NOME_DO_ARQUIVO), null);
        return new ServidorMcp(json, List.of(new ResumoFinanceiroMcp(financas, json),
                new RegistrarGastoMcp(financas, json), new RegistrarGanhoMcp(financas, json)));
    }

    private JsonNode tratar(ServidorMcp servidor, String corpo) {
        return json.readTree(servidor.tratar(corpo).orElseThrow());
    }

    @Test
    @DisplayName("initialize devolve a versão pedida quando a conhece, e a mais nova quando não")
    void negociaAVersao() {
        var servidor = servidor();
        JsonNode conhecida = tratar(servidor, """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",
                 "capabilities":{},"clientInfo":{"name":"hermes","version":"1"}}}""");
        assertEquals("2025-06-18", conhecida.path("result").path("protocolVersion").stringValue());
        assertEquals("jarvis", conhecida.path("result").path("serverInfo").path("name").stringValue());
        assertTrue(conhecida.path("result").path("capabilities").path("tools").isObject());

        JsonNode outra = tratar(servidor, """
                {"jsonrpc":"2.0","id":"a","method":"initialize","params":{"protocolVersion":"1999-01-01"}}""");
        assertEquals(ServidorMcp.VERSOES.get(0),
                outra.path("result").path("protocolVersion").stringValue());
        assertEquals("a", outra.path("id").stringValue());
    }

    @Test
    @DisplayName("tools/list traz as ferramentas em ordem alfabética, com o esquema")
    void listaAsFerramentas() {
        JsonNode resposta = tratar(servidor(), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        JsonNode tools = resposta.path("result").path("tools");
        assertEquals(3, tools.size());
        assertEquals("registrar_ganho", tools.get(0).path("name").stringValue());
        assertEquals("registrar_gasto", tools.get(1).path("name").stringValue());
        assertEquals("resumo_financeiro", tools.get(2).path("name").stringValue());
        assertEquals("object", tools.get(1).path("inputSchema").path("type").stringValue());
    }

    @Test
    @DisplayName("tools/call lança no livro, e argumento errado volta no resultado com isError")
    void chamaAFerramenta() {
        var servidor = servidor();
        JsonNode ok = tratar(servidor, """
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"registrar_gasto",
                 "arguments":{"valor":49.9,"categoria":"combustível","descricao":"posto"}}}""");
        assertFalse(ok.path("result").path("isError").booleanValue());
        assertTrue(ok.path("result").path("content").get(0).path("text").stringValue()
                .startsWith("Lancei. Gasto de R$ 49,90 em combustível (posto)"));
        assertEquals(4_990, financas.resumo(PeriodoFinanceiro.MES_ATUAL).gastos());

        JsonNode torto = tratar(servidor, """
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"registrar_gasto",
                 "arguments":{"valor":"cinquenta","categoria":"x"}}}""");
        assertTrue(torto.path("result").path("isError").booleanValue());
        assertTrue(torto.path("error").isMissingNode(), "erro de argumento não é erro JSON-RPC");
    }

    @Test
    @DisplayName("ferramenta e método desconhecidos são erro JSON-RPC")
    void desconhecidos() {
        var servidor = servidor();
        JsonNode ferramenta = tratar(servidor, """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"controlar_pc","arguments":{}}}""");
        assertEquals(ServidorMcp.ERRO_PARAMETROS, ferramenta.path("error").path("code").intValue());

        JsonNode metodo = tratar(servidor, "{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/list\"}");
        assertEquals(ServidorMcp.ERRO_METODO, metodo.path("error").path("code").intValue());
        assertEquals(6, metodo.path("id").intValue());
    }

    @Test
    @DisplayName("notificação não tem resposta; JSON torto, lote e id inválido têm")
    void bordas() {
        var servidor = servidor();
        assertTrue(servidor.tratar("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .isEmpty());
        assertEquals(ServidorMcp.ERRO_JSON,
                tratar(servidor, "{nao e json").path("error").path("code").intValue());
        assertEquals(ServidorMcp.ERRO_REQUISICAO,
                tratar(servidor, "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")
                        .path("error").path("code").intValue());
        assertEquals(ServidorMcp.ERRO_REQUISICAO,
                tratar(servidor, "{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}")
                        .path("error").path("code").intValue());
        assertTrue(tratar(servidor, "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}")
                .path("result").isObject());
    }
}
