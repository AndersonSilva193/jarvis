package dev.jarvis.escritorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.escritorio.EscritaNoPc.Estado;
import dev.jarvis.escritorio.EscritaNoPc.Resposta;
import dev.jarvis.mcp.AlterarCompromissoMcp;
import dev.jarvis.mcp.CancelarCompromissoMcp;
import dev.jarvis.mcp.RegistrarCompromissoMcp;
import dev.jarvis.mcp.ServidorMcp;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A agenda escrita pelos agentes no agenda.csv da pasta do PC. */
class AgendaEscritaTest {

    @TempDir Path pasta;

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<PedidoDeEscrita> enviados = new ArrayList<>();
    private Estado estadoDoPc = Estado.GRAVADO;

    private AgendaService agenda() {
        FilaDoPc fila = new FilaDoPc(p -> { enviados.add(p); return new Resposta(estadoDoPc, ""); },
                pasta.resolve("pendentes-pc.json"), json, t -> { }, Runnable::run);
        JarvisProperties props = new JarvisProperties(null, "America/Sao_Paulo", null, null, null, null, null,
                null, null, null, null, null, null);
        return new AgendaService(null, props, new RelogioFalso("2026-11-11T15:00:00Z"), fila);
    }

    private static List<String> textos(JsonNode no) {
        List<String> lista = new ArrayList<>();
        for (JsonNode item : no) lista.add(item.asString());
        return lista;
    }

    @Test
    void registrarMandaLinhaComIdNoCabecalhoDaAgenda() {
        AgendaService.Gravacao g = agenda().registrar("12/11/2026", "14:00", "", "Reunião com cliente", "Batel");
        assertEquals(Estado.GRAVADO, g.estado());
        assertTrue(g.id().matches("[a-f][0-9a-f]{7}"), "o Excel transforma id só de dígitos em número: " + g.id());
        PedidoDeEscrita pedido = enviados.get(0);
        assertEquals("acrescentar_linhas", pedido.acao());
        assertEquals("agenda.csv", pedido.arquivo());
        assertEquals(AgendaService.CABECALHO_CSV, textos(pedido.dados().path("cabecalho")));
        assertEquals(List.of(g.id(), "12/11/2026", "14:00", "", "Reunião com cliente", "Batel"),
                textos(pedido.dados().path("linhas").get(0)));
    }

    @Test
    void aceitaDataIsoEHoraSemZero() {
        agenda().registrar("2026-11-12", "9:00", "10h", "Dentista", "");
        List<String> linha = textos(enviados.get(0).dados().path("linhas").get(0));
        assertEquals("12/11/2026", linha.get(1));
        assertEquals("09:00", linha.get(2));
        assertEquals("10:00", linha.get(3));
    }

    @Test
    void recusaEntradaInvalida() {
        AgendaService agenda = agenda();
        assertThrows(IllegalArgumentException.class, () -> agenda.registrar("31/02/2026", "", "", "X", ""));
        assertThrows(IllegalArgumentException.class, () -> agenda.registrar("amanhã", "", "", "X", ""));
        assertThrows(IllegalArgumentException.class, () -> agenda.registrar("12/11/2026", "25:00", "", "X", ""));
        assertThrows(IllegalArgumentException.class, () -> agenda.registrar("12/11/2026", "", "", "  ", ""));
        assertThrows(IllegalArgumentException.class,
                () -> agenda.registrar("12/11/2026", "", "", "x".repeat(81), ""));
        assertTrue(enviados.isEmpty(), "nada vai à fila");
    }

    @Test
    void alterarMandaSoOsCamposPedidos() {
        AgendaService.Gravacao g = agenda().alterar("a1b2c3d4", Map.of("inicio", "15:30", "compromisso", "Reunião (remarcada)"));
        assertEquals(Estado.GRAVADO, g.estado());
        PedidoDeEscrita pedido = enviados.get(0);
        assertEquals("alterar_linha", pedido.acao());
        assertEquals("a1b2c3d4", pedido.dados().path("id").asString());
        JsonNode valores = pedido.dados().path("valores");
        assertEquals(2, valores.size());
        assertEquals("15:30", valores.path("Início").asString());
        assertEquals("Reunião (remarcada)", valores.path("Compromisso").asString());
        assertThrows(IllegalArgumentException.class, () -> agenda().alterar("a1b2c3d4", Map.of("cor", "azul")));
        assertThrows(IllegalArgumentException.class, () -> agenda().alterar("a1b2c3d4", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> agenda().alterar("../x", Map.of("local", "y")));
    }

    @Test
    void cancelarMandaRemoverLinha() {
        AgendaService.Gravacao g = agenda().cancelar("a1b2c3d4");
        assertEquals("a1b2c3d4", g.id());
        assertEquals("remover_linha", enviados.get(0).acao());
        assertEquals("a1b2c3d4", enviados.get(0).dados().path("id").asString());
    }

    @Test
    void recusaDoPcVoltaComoErroParaQuemPediu() {
        estadoDoPc = Estado.RECUSADO;
        var saida = PedidosDaAgenda.registrar(agenda(), json.readTree(
                "{\"data\":\"12/11/2026\",\"compromisso\":\"Dentista\"}"));
        assertTrue(saida.erro(), saida.texto());
        assertTrue(saida.texto().contains("recusou"), saida.texto());
    }

    @Test
    void pcLigadoSemConfirmarNaoDizQueEleVaiVoltar() {
        estadoDoPc = Estado.FALHOU;
        var saida = PedidosDaAgenda.registrar(agenda(), json.readTree(
                "{\"data\":\"12/11/2026\",\"compromisso\":\"Dentista\"}"));
        assertFalse(saida.erro());
        assertFalse(saida.texto().contains("quando o PC voltar"), saida.texto());
        assertTrue(saida.texto().contains("tento de novo"), saida.texto());
    }

    @Test
    void pcForaDevolveOEstadoDaFila() {
        estadoDoPc = Estado.PC_OFFLINE;
        assertEquals(Estado.PC_OFFLINE, agenda().registrar("12/11/2026", "", "", "Dentista", "").estado());
    }

    @Test
    void agendaDeHojeMostraOId() {
        var cabecalho = List.of("Id", "Data", "Início", "Fim", "Compromisso", "Local");
        var linhas = List.of(List.of("a1b2c3d4", "11/11/2026", "14:00", "", "Dentista", ""));
        var compromissos = AgendaService.doDia(cabecalho, linhas, LocalDate.of(2026, 11, 11));
        assertEquals("a1b2c3d4", compromissos.get(0).id());
        String texto = AgendaService.texto(LocalDate.of(2026, 11, 11), compromissos, LocalDate.of(2026, 11, 11));
        assertTrue(texto.contains("Dentista (id a1b2c3d4)"), texto);
    }

    @Test
    void mcpListaAsTresERecusaArgumentoErrado() {
        AgendaService agenda = agenda();
        ServidorMcp servidor = new ServidorMcp(json, List.of(new RegistrarCompromissoMcp(agenda, json),
                new AlterarCompromissoMcp(agenda, json), new CancelarCompromissoMcp(agenda, json)));
        JsonNode tools = json.readTree(servidor.tratar("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}")
                .orElseThrow()).path("result").path("tools");
        assertEquals(List.of("alterar_compromisso", "cancelar_compromisso", "registrar_compromisso"),
                List.of(tools.get(0).path("name").asString(), tools.get(1).path("name").asString(),
                        tools.get(2).path("name").asString()));

        JsonNode ok = json.readTree(servidor.tratar("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"registrar_compromisso",
                 "arguments":{"data":"2026-11-12","inicio":"14:00","compromisso":"Reunião"}}}""").orElseThrow()).path("result");
        assertFalse(ok.path("isError").booleanValue());
        assertTrue(ok.path("content").get(0).path("text").asString().startsWith("Marquei"), ok.toString());
        assertTrue(ok.path("structuredContent").path("noPc").booleanValue());
        assertTrue(ok.path("structuredContent").path("id").asString().matches("[0-9a-f]{8}"));

        JsonNode torto = json.readTree(servidor.tratar("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"registrar_compromisso",
                 "arguments":{"data":"ontem à tarde","compromisso":"x"}}}""").orElseThrow()).path("result");
        assertTrue(torto.path("isError").booleanValue());
    }
}
