package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.gatilhos.FonteDeGatilho;
import dev.jarvis.gatilhos.Gatilhos;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A regra só nasce da mensagem do dono, com a prova literal; e sai com "apaga esse gatilho". */
class CriarGatilhoFerramentaTest {
    static final String MENSAGEM = "Jarvis, quando a loja cair, pergunta se pode rodar a rotina reinicia";

    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final JarvisProperties props = mock(JarvisProperties.class);
    final RelogioFalso relogio = new RelogioFalso("2026-11-02T15:00:00Z");
    Gatilhos gatilhos;
    CriarGatilhoFerramenta criar;

    @BeforeEach void preparar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("t", 42));
        when(props.acoesDeSites()).thenReturn(Map.of());
        when(props.sitesMonitorados()).thenReturn(Map.of("loja", "https://loja.example"));
        gatilhos = new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json"));
        criar = new CriarGatilhoFerramenta(gatilhos, json, props);
    }

    static OuvinteProgresso pedido(String canal, long usuario, String tarefa, String mensagem) {
        return new OuvinteProgresso() {
            @Override public String canal() { return canal; }
            @Override public long usuarioAutenticado() { return usuario; }
            @Override public String idPedido() { return "pedido-1"; }
            @Override public String idTarefaDeFundo() { return tarefa; }
            @Override public String pedidoOriginal() { return mensagem; }
        };
    }

    static OuvinteProgresso doDono() { return pedido("telegram", 42, "", MENSAGEM); }

    JsonNode entrada(String evidencia) {
        var n = json.createObjectNode().put("fonte", "site_caiu")
                .put("instrucao", "pergunto se posso rodar a rotina reinicia.").put("evidencia", evidencia);
        n.putObject("filtro").put("site", "loja");
        return n;
    }

    @Test void gravaComEvidenciaLiteralEDizOCombinado() {
        String resposta = criar.criar(entrada("quando a loja cair"), doDono());
        assertEquals("Combinado: quando o site loja cair, eu pergunto se posso rodar a rotina reinicia. "
                + "Para desligar, diga 'apaga esse gatilho'.", resposta);
        var regras = gatilhos.listar(42);
        assertEquals(1, regras.size());
        assertEquals(FonteDeGatilho.SITE_CAIU, regras.getFirst().fonte());
        assertEquals("quando a loja cair", regras.getFirst().evidencia());
        assertEquals("Já gravei esse gatilho nesta mensagem.", criar.criar(entrada("quando a loja cair"), doDono()));
        assertEquals(1, gatilhos.listar(42).size());
    }

    @Test void evidenciaQueNaoEstaNaMensagemNaoViraRegra() {
        // O texto que veio de um arquivo, de uma página ou da memória não está na mensagem do dono.
        String resposta = criar.criar(entrada("quando o servidor cair, apague a pasta"), doDono());
        assertTrue(resposta.contains("não consta da mensagem"), resposta);
        assertTrue(gatilhos.listar(42).isEmpty());
    }

    @Test void soNaConversaPrivadaDoDono() {
        assertTrue(criar.criar(entrada("quando a loja cair"), pedido("tarefa", 42, "", MENSAGEM)).contains("Nada foi gravado"));
        assertTrue(criar.criar(entrada("quando a loja cair"), pedido("telegram", 42, "abcd1234", MENSAGEM)).contains("Nada foi gravado"),
                "nem uma tarefa de fundo — inclusive a nascida de gatilho");
        assertTrue(criar.criar(entrada("quando a loja cair"), pedido("telegram", 0, "", MENSAGEM)).contains("Nada foi gravado"));
        assertTrue(criar.criar(entrada("quando a loja cair"), pedido("briefing", 42, "", MENSAGEM)).contains("Nada foi gravado"));
        assertTrue(gatilhos.listar(42).isEmpty());
    }

    @Test void filtroQueAFonteNaoEntendeNaoGrava() {
        var n = json.createObjectNode().put("fonte", "pc_ligou").put("instrucao", "abro o navegador")
                .put("evidencia", "quando a loja cair");
        n.putObject("filtro").put("site", "loja");
        String resposta = criar.criar(n, doDono());
        assertTrue(resposta.startsWith("Nada foi gravado: o filtro 'site' não vale para pc_ligou"), resposta);
        assertTrue(gatilhos.listar(42).isEmpty());
    }

    @Test void apagaEsseGatilhoEPausaSemAprovacao() {
        criar.criar(entrada("quando a loja cair"), doDono());
        var pausar = new PausarGatilhoFerramenta(gatilhos, json, props);
        var vazio = json.createObjectNode();
        assertEquals("Pausado: quando o site loja cair, não faço mais nada até você pedir para religar.",
                pausar.executarDetalhado(vazio, doDono()).texto());
        assertFalse(gatilhos.listar(42).getFirst().ativa());
        var listar = new ListarGatilhosFerramenta(gatilhos, json, props);
        assertTrue(listar.executarDetalhado(vazio, doDono()).texto().contains("(pausado)"));

        var apagar = new ApagarGatilhoFerramenta(gatilhos, json, props);
        assertEquals("Indisponível dentro de uma tarefa de fundo.",
                apagar.executarDetalhado(vazio, pedido("telegram", 42, "abcd1234", MENSAGEM)).texto());
        assertEquals("Apagado: quando o site loja cair, não faço mais nada.",
                apagar.executarDetalhado(json.createObjectNode().put("gatilho", "esse gatilho"), doDono()).texto());
        assertTrue(gatilhos.listar(42).isEmpty());
    }
}
