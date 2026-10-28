package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.node.StringNode;

/** O formato neutro indo ao Ollama pelo agente dublado e voltando, e o que acontece quando volta torto. */
class ProvedorLocalTest {

    private static final tools.jackson.databind.json.JsonMapper JSON = BancadaLocal.JSON;

    @TempDir Path pasta;
    private BancadaLocal bancada;

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private BancadaLocal bancada() {
        bancada = new BancadaLocal(pasta);
        return bancada;
    }

    private static DefinicaoDeFerramenta clima() {
        ObjectNode esquema = JSON.createObjectNode().put("type", "object");
        esquema.putObject("properties").putObject("dias").put("type", "integer")
                .put("minimum", 1).put("maximum", 7);
        esquema.putArray("required").add("dias");
        return new DefinicaoDeFerramenta("clima", "Previsão do tempo.", esquema, true);
    }

    private static PedidoAoModelo pedido(List<Mensagem> mensagens, ListaDeFerramentas ferramentas,
            OpcoesDoPedido opcoes) {
        return new PedidoAoModelo(InstrucoesDeSistema.de(new BlocoDeSistema("Você é o Jarvis.", "5m"),
                new BlocoDeSistema("Hoje é terça.", null)), mensagens, ferramentas, opcoes);
    }

    private static PedidoAoModelo simples(String texto) {
        return pedido(List.of(Mensagem.texto(Mensagem.Papel.USUARIO, texto)),
                new ListaDeFerramentas(List.of(clima()), false, List.of()), OpcoesDoPedido.de(Origem.BRIEFING));
    }

    @Test
    @DisplayName("ida: system primeiro, resultado como tool, nada da Anthropic; volta: texto, motivo e uso")
    void idaEVolta() throws Exception {
        var b = bancada().responde("Amanhã faz 25 graus.");
        JsonNode original = JSON.createObjectNode().put("type", "tool_use").put("id", "toolu_1")
                .put("name", "clima").put("assinatura_secreta", "x");
        ObjectNode entrada = JSON.createObjectNode().put("dias", 1);
        List<Mensagem> mensagens = List.of(
                Mensagem.texto(Mensagem.Papel.USUARIO, "como está o tempo?"),
                new Mensagem(Mensagem.Papel.ASSISTENTE, List.of(
                        new Bloco.Opaco("anthropic", JSON.createObjectNode().put("type", "thinking")
                                .put("signature", "abc"), true, ""),
                        new Bloco.Texto("Vou ver.", Bloco.Opaco.de("anthropic",
                                JSON.createObjectNode().put("type", "text").put("citations", "c"))),
                        new Bloco.Chamada("toolu_1", "clima", entrada, Bloco.Opaco.de("anthropic", original)))),
                new Mensagem(Mensagem.Papel.USUARIO, List.of(
                        new Bloco.Resultado("toolu_1", "22 graus", false),
                        new Bloco.Texto("e amanhã?"))));
        var ferramentas = new ListaDeFerramentas(List.of(clima()), true,
                List.of(Bloco.Opaco.de("anthropic", JSON.createObjectNode().put("type", "web_search_20250305"))));

        RespostaDoModelo resposta = b.local.responder(pedido(mensagens, ferramentas,
                OpcoesDoPedido.de(Origem.BRIEFING)));

        JsonNode enviado = b.recebidos.getFirst();
        String bruto = JSON.writeValueAsString(enviado);
        for (String proibido : new String[] {"anthropic", "signature", "citations", "assinatura_secreta",
                "web_search", "cache_control", "thinking"}) {
            assertFalse(bruto.contains(proibido), "não pode ir ao local: " + proibido);
        }
        JsonNode msgs = enviado.path("messages");
        assertEquals("system", msgs.get(0).path("role").asString());
        assertEquals("Você é o Jarvis.\n\nHoje é terça.", msgs.get(0).path("content").asString());
        assertEquals("user", msgs.get(1).path("role").asString());
        assertEquals("assistant", msgs.get(2).path("role").asString());
        assertEquals("Vou ver.", msgs.get(2).path("content").asString());
        assertEquals("clima", msgs.get(2).path("tool_calls").get(0).path("function").path("name").asString());
        assertEquals(1, msgs.get(2).path("tool_calls").get(0).path("function").path("arguments").path("dias").asInt());
        assertEquals("tool", msgs.get(3).path("role").asString());
        assertEquals("clima", msgs.get(3).path("tool_name").asString());
        assertEquals("22 graus", msgs.get(3).path("content").asString());
        assertEquals("user", msgs.get(4).path("role").asString());
        assertEquals("e amanhã?", msgs.get(4).path("content").asString());
        assertEquals(1, enviado.path("tools").size());
        assertEquals("clima", enviado.path("tools").get(0).path("function").path("name").asString());
        assertFalse(enviado.path("think").asBoolean(true), "apoio não pensa");

        assertEquals(List.of("Amanhã faz 25 graus."), resposta.textos());
        assertEquals(MotivoDeParada.FIM, resposta.motivo());
        assertEquals(120, resposta.uso().entrada());
        assertEquals(30, resposta.uso().saida());
        var hoje = b.medidor.localDeHoje();
        assertEquals(1L, hoje.chamadas().get(Origem.BRIEFING));
        assertEquals(120, hoje.entrada());
    }

    @Test
    @DisplayName("resultado de erro vai escrito no conteúdo, porque o Ollama não tem is_error")
    void resultadoDeErro() throws Exception {
        var b = bancada().responde("ok");
        b.local.responder(pedido(List.of(
                new Mensagem(Mensagem.Papel.ASSISTENTE, List.of(
                        new Bloco.Chamada("t1", "clima", JSON.createObjectNode().put("dias", 1)))),
                new Mensagem(Mensagem.Papel.USUARIO, List.of(new Bloco.Resultado("t1", "sem rede", true)))),
                ListaDeFerramentas.vazia(), OpcoesDoPedido.de(Origem.ANOTADOR)));
        assertEquals("Erro: sem rede", b.recebidos.getFirst().path("messages").get(2).path("content").asString());
        assertTrue(b.recebidos.getFirst().path("tools").isMissingNode(), "sem ferramenta, sem tools");
    }

    @Test
    @DisplayName("chamada válida volta com id próprio, argumento conferido e motivo FERRAMENTA")
    void chamadaValida() throws Exception {
        var b = bancada().responde(BancadaLocal.comChamadas("", "clima", JSON.createObjectNode().put("dias", 3)));
        RespostaDoModelo resposta = b.local.responder(simples("previsão?"));

        assertEquals(MotivoDeParada.FERRAMENTA, resposta.motivo());
        Bloco.Chamada chamada = assertInstanceOf(Bloco.Chamada.class, resposta.blocos().getFirst());
        assertTrue(chamada.id().startsWith("local_"));
        assertEquals(3, chamada.entrada().path("dias").asInt());
        assertEquals(0, b.medidor.localDeHoje().errosDeFormato());
    }

    @Test
    @DisplayName("argumento que não é JSON volta ao modelo como erro, sem chegar a quem chamou")
    void argumentoQueNaoEJson() throws Exception {
        var b = bancada()
                .responde(BancadaLocal.comChamadas("", "clima", StringNode.valueOf("{dias: tres")))
                .responde("Desculpe, a previsão é de sol.");

        RespostaDoModelo resposta = b.local.responder(simples("previsão?"));

        assertEquals(List.of("Desculpe, a previsão é de sol."), resposta.textos());
        assertTrue(resposta.blocos().stream().noneMatch(Bloco.Chamada.class::isInstance),
                "chamada torta nunca sai para execução");
        assertEquals(2, b.recebidos.size(), "uma volta de correção");
        JsonNode correcao = b.recebidos.get(1).path("messages");
        JsonNode eco = correcao.get(correcao.size() - 2);
        assertEquals("assistant", eco.path("role").asString());
        assertTrue(eco.path("tool_calls").get(0).path("function").path("arguments").isObject(),
                "o eco leva objeto, que é o que o agente aceita");
        JsonNode erro = correcao.get(correcao.size() - 1);
        assertEquals("tool", erro.path("role").asString());
        assertTrue(erro.path("content").asString().startsWith("Erro de formato: os argumentos não são JSON"));
        assertEquals(1, b.medidor.localDeHoje().errosDeFormato());
    }

    @Test
    @DisplayName("argumento fora do esquema duas vezes: erro_de_formato, e as duas contam")
    void argumentoForaDoEsquemaPersistente() throws Exception {
        var foraDoEsquema = JSON.createObjectNode().put("dias", 30);
        var b = bancada()
                .responde(BancadaLocal.comChamadas("", "clima", foraDoEsquema))
                .responde(BancadaLocal.comChamadas("", "clima", foraDoEsquema));

        FalhaLocal falha = assertThrows(FalhaLocal.class, () -> b.local.responder(simples("previsão?")));

        assertEquals(ProvedorLocal.ERRO_DE_FORMATO, falha.motivo());
        assertTrue(b.recebidos.get(1).toString().contains("no máximo 7"), "o motivo vai ao modelo");
        assertEquals(2, b.medidor.localDeHoje().errosDeFormato());
    }

    @Test
    @DisplayName("ferramenta que não foi oferecida é erro de formato")
    void ferramentaInexistente() throws Exception {
        var b = bancada()
                .responde(BancadaLocal.comChamadas("", "apagar_tudo", JSON.createObjectNode()))
                .responde("{\"decisao\":\"sim\"}");
        var pedido = new PedidoAoModelo(InstrucoesDeSistema.de(new BlocoDeSistema("classifique", null)),
                List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "pode")), ListaDeFerramentas.vazia(),
                OpcoesDoPedido.curta(Origem.CLASSIFICADOR, 60, Duration.ofSeconds(5)));

        RespostaDoModelo resposta = b.local.responder(pedido);

        assertEquals(List.of("{\"decisao\":\"sim\"}"), resposta.textos());
        assertTrue(b.recebidos.get(1).toString().contains("não existe neste pedido"));
        assertEquals(1, b.medidor.localDeHoje().errosDeFormato());
    }

    @Test
    @DisplayName("o agente calado estoura o prazo e o inferir é cancelado")
    void timeoutCancela() throws Exception {
        var b = bancada().calado();

        FalhaLocal falha = assertThrows(FalhaLocal.class, () -> b.local.responder(simples("oi")));

        assertEquals(ProvedorLocal.TIMEOUT, falha.motivo());
        var operacao = b.e2.estado(b.ids.getFirst()).orElseThrow();
        assertTrue(operacao.cancelar() || "cancelado".equals(operacao.estado()),
                "o agente é avisado para não começar");
    }

    @Test
    @DisplayName("o codigoErro do agente vira o motivo da falha")
    void codigoDoAgente() throws Exception {
        var b = bancada().falha("sem_memoria");
        FalhaLocal falha = assertThrows(FalhaLocal.class, () -> b.local.responder(simples("oi")));
        assertEquals("sem_memoria", falha.motivo());
    }

    @Test
    @DisplayName("prazo: classificador o dele, introdução 10 s, o resto o configurado")
    void prazoPorQuemEspera() {
        var props = new JarvisProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1,
                        new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)));
        var local = new ProvedorLocal(null, null, props, JSON);

        assertEquals(Duration.ofSeconds(5),
                local.prazo(OpcoesDoPedido.curta(Origem.CLASSIFICADOR, 60, Duration.ofSeconds(5))));
        assertEquals(ProvedorLocal.PRAZO_COM_ALGUEM_ESPERANDO, local.prazo(OpcoesDoPedido.de(Origem.INICIALIZACAO)));
        assertEquals(Duration.ofSeconds(60),
                local.prazo(OpcoesDoPedido.curta(Origem.ANOTADOR, 900, Duration.ofSeconds(20))));
        assertEquals(Duration.ofSeconds(60), local.prazo(OpcoesDoPedido.de(Origem.BRIEFING)));
    }
}
