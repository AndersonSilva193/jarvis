package dev.jarvis.conversa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import dev.jarvis.custo.Origem;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ia.*;
import dev.jarvis.web.ConversaController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

class ConversaServiceTest {
    @Test void hudExplicaFalhasDeModeloEDeCriacaoSemExporCorpoDaApi() {
        assertEquals("A API do modelo recusou o pedido (HTTP 400): invalid_request_error: messages.3.content inválido",
                ConversaService.motivoDaFalha(new ErroDaApiDoModelo(400,
                        "invalid_request_error: messages.3.content inválido")));
        assertEquals("A API do modelo recusou o pedido (HTTP 400). Verifique o log do Jarvis na VPS.",
                ConversaService.motivoDaFalha(new java.io.IOException(
                        "API do Claude respondeu HTTP 400: segredo do provedor")));
        assertTrue(ConversaService.motivoDaFalha(new IllegalStateException(
                "precisei de passos demais para resolver isso. Nome inválido."))
                .contains("Nome inválido"));
    }
    private final JsonMapper json = JsonMapper.builder().build();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final List<EventoConversa> eventos = new CopyOnWriteArrayList<>();
    private final CountDownLatch concluido = new CountDownLatch(1);
    private final RegistroConversas registro = new RegistroConversas(e -> {
        EventoConversa evento = (EventoConversa) e;
        eventos.add(evento);
        if (evento.nome().equals("conversa.resposta") || evento.nome().equals("conversa.erro")) concluido.countDown();
    });
    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), new JarvisProperties.Anthropic("teste", "modelo", 1000, 1000),
            null, null, new JarvisProperties.Hud("teste", ""), null, null, null, null, null);
    private final AnexosService anexos = new AnexosService();
    private final Assistente assistente = new Assistente(claude, new Ferramentas(List.of(), json),
            new SistemaDoJarvis(new PersonalidadeService(props), json, props),
            json, registro, anexos, dev.jarvis.ia.BuscaWebServidor.desligada());
    private final ConversaService servico = new ConversaService(assistente, registro, props);

    @AfterEach void fechar() { servico.fechar(); anexos.fechar(); }
    private JsonNode resposta(String texto) {
        var r = json.createObjectNode().put("stop_reason", "end_turn");
        var blocos = r.putArray("content");
        blocos.addObject().put("type", "thinking").put("thinking", "interno").put("signature", "sig");
        blocos.addObject().put("type", "text").put("text", texto);
        return r;
    }

    @Test void aceitaSemEsperarModeloUsaVirtualThreadERejeitaDuplicado() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1), liberar = new CountDownLatch(1);
        AtomicBoolean virtual = new AtomicBoolean();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenAnswer(chamada -> {
            virtual.set(Thread.currentThread().isVirtual()); entrou.countDown();
            assertTrue(liberar.await(5, TimeUnit.SECONDS)); return resposta("Pronto.");
        });
        try {
            var controlador = new ConversaController(servico);
            var aceito = assertTimeoutPreemptively(Duration.ofSeconds(1),
                    () -> controlador.enviar(new ConversaController.Pedido("olá")));
            assertEquals(202, aceito.getStatusCode().value());
            assertTrue(entrou.await(2, TimeUnit.SECONDS));
            assertTrue(virtual.get());
            assertThrows(RegistroConversas.OcupadaException.class, () -> servico.enviar("outro"));
            var historico = assertTimeoutPreemptively(Duration.ofMillis(200), servico::historico);
            assertEquals("processando", historico.emAndamento().estado());
            assertThrows(RegistroConversas.OcupadaException.class, servico::limpar);
        } finally { liberar.countDown(); }
        assertTrue(concluido.await(3, TimeUnit.SECONDS));
        assertNull(servico.historico().emAndamento());
        assertEquals("Pronto.", servico.historico().ultima().texto());
        assertEquals(1, servico.historico().trocas().size());
    }

    @Test void telegramEHudUsamMesmoHistoricoEALimpezaRemoveUltima() throws Exception {
        List<ArrayNode> mensagens = new CopyOnWriteArrayList<>();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenAnswer(chamada -> {
            // Origem 0, perfil de esforço 1, system 2 — então as mensagens são o argumento 3.
            mensagens.add(((ArrayNode) chamada.getArgument(3)).deepCopy()); return resposta("Entendido.");
        });
        assistente.responder(42, "me chame de chefe");
        assertNull(registro.historico(42).ultima());
        assertEquals(1, eventos.size()); // Telegram só avisa que mudou o histórico público.
        servico.enviar("como deve me chamar?");
        assertTrue(concluido.await(3, TimeUnit.SECONDS));
        assertTrue(mensagens.get(1).toString().contains("me chame de chefe"));
        assertFalse(mensagens.get(1).toString().contains("thinking"));
        assertEquals(List.of("telegram", "hud"), servico.historico().trocas().stream().map(RegistroConversas.Troca::canal).toList());
        assertFalse(json.writeValueAsString(servico.historico()).contains("interno"));
        assistente.limparHistorico(42);
        assertTrue(servico.historico().trocas().isEmpty());
        assertNull(servico.historico().ultima());
    }

    @Test void erroTerminalPodeSerRecuperadoSemExporDetalhes() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenThrow(new IllegalStateException("segredo-do-provedor"));
        servico.enviar("oi");
        assertTrue(concluido.await(3, TimeUnit.SECONDS));
        assertNotNull(servico.historico().ultima().erro());
        assertFalse(servico.historico().ultima().erro().contains("segredo"));
        assertNull(servico.historico().emAndamento());
        assertTrue(servico.historico().trocas().isEmpty());
    }

    @Test void limitaDezTrocasENaoPublicaTarefas() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenReturn(resposta("ok"));
        for (int i = 0; i < 12; i++) assistente.responder(42, "mensagem " + i);
        assertEquals(10, servico.historico().trocas().size());
        assertEquals("mensagem 2", servico.historico().trocas().getFirst().pedido());
        assistente.executarTarefa("tarefa isolada");
        assertEquals(10, servico.historico().trocas().size());
    }

    @Test void textoInvalidoNaoChegaAoModelo() {
        var controlador = new ConversaController(servico);
        for (String texto : new String[]{null, "  ", "x".repeat(8001)})
            assertEquals(400, controlador.enviar(new ConversaController.Pedido(texto)).getStatusCode().value());
        verifyNoInteractions(claude);
        assertEquals(204, controlador.ultima().getStatusCode().value());
    }

    @Test void falhaDoObservadorNaoQuebraResposta() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class))).thenReturn(resposta("ok"));
        assertEquals("ok", assistente.responder(42, "oi", Canal.HUD, new OuvinteProgresso() {
            @Override public void iniciado() { throw new IllegalStateException("tela caiu"); }
        }).texto());
    }

    @Test void fluxoHttpAteRecuperacaoComAssistenteRealEModeloSimulado() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("Resposta integrada"));
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new ConversaController(servico))
                .addFilters(new dev.jarvis.web.TokenHudFiltro(props)).build();
        var recebido = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/api/conversa").header("Authorization", "Bearer teste")
                .contentType("application/json").content("{\"texto\":\"Mensagem da HUD\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(recebido).path("id").asText();
        assertTrue(concluido.await(3, TimeUnit.SECONDS));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/conversa/ultima").header("Authorization", "Bearer teste"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.id").value(id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.texto").value("Resposta integrada"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/api/conversa/historico").header("Authorization", "Bearer teste"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.trocas[0].pedido").value("Mensagem da HUD"));
        verify(claude, times(1)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
    }

    @Test void hudEsperaTelegramSemBloquearConsultaOuMisturarExecucoes() throws Exception {
        CountDownLatch entrou = new CountDownLatch(1), liberar = new CountDownLatch(1);
        var chamadas = new java.util.concurrent.atomic.AtomicInteger();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenAnswer(chamada -> {
                    if (chamadas.incrementAndGet() == 1) { entrou.countDown(); assertTrue(liberar.await(5, TimeUnit.SECONDS)); }
                    return resposta("ok");
                });
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var telegram = executor.submit(() -> assistente.responder(42, "Telegram primeiro"));
            try {
                assertTrue(entrou.await(2, TimeUnit.SECONDS));
                servico.enviar("HUD depois");
                assertEquals("aguardando", servico.historico().emAndamento().estado());
                assertEquals(1, chamadas.get());
            } finally { liberar.countDown(); }
            assertEquals("ok", telegram.get(3, TimeUnit.SECONDS));
            assertTrue(concluido.await(3, TimeUnit.SECONDS));
            assertEquals(List.of("telegram", "hud"), servico.historico().trocas().stream().map(RegistroConversas.Troca::canal).toList());
        }
    }

    @Test void ferramentaPublicaProgressoEAnexosSemVazarResultadosInternos(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path pasta) throws Exception {
        var arquivo = java.nio.file.Files.writeString(pasta.resolve("teste.txt"), "artefato simulado");
        var ferramenta = new dev.jarvis.ferramentas.Ferramenta() {
            public String nome() { return "noticias"; }
            public String descricao() { return "Ferramenta simulada"; }
            public tools.jackson.databind.node.ObjectNode esquemaEntrada() { return json.createObjectNode(); }
            public String executar(JsonNode entrada) { return "interno"; }
            public dev.jarvis.ferramentas.ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
                progresso.ferramenta(nome(), "consultando notícias", 1, 2);
                return new dev.jarvis.ferramentas.ResultadoFerramenta("resultado interno", List.of(
                        new dev.jarvis.ferramentas.ResultadoFerramenta.Artefato(arquivo, "teste.txt", "text/plain")),
                        List.of(new dev.jarvis.ferramentas.ResultadoFerramenta.Referencia("Fonte", "https://example.org/fonte")),
                        new Confirmacao("1234", "Apenas teste", "2026-10-14T23:00:00Z"),
                        List.of(), List.of());
            }
        };
        var executar = json.createObjectNode().put("stop_reason", "tool_use");
        executar.putArray("content").addObject().put("type", "tool_use").put("id", "t1")
                .put("name", "noticias").putObject("input");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(executar, resposta("Pronto"));
        var agente = new Assistente(claude, new Ferramentas(List.of(ferramenta), json),
                new SistemaDoJarvis(new PersonalidadeService(props), json, props), json, registro, anexos,
                dev.jarvis.ia.BuscaWebServidor.desligada());
        List<String> progresso = new java.util.ArrayList<>();
        var resultado = agente.responder(42, "teste", Canal.HUD, new OuvinteProgresso() {
            public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                progresso.add(descricao);
                throw new IllegalStateException("observador desconectado");
            }
        });
        assertEquals(List.of("consultando notícias", "consultando notícias"), progresso);
        assertEquals(2, resultado.anexos().size());
        assertTrue(anexos.obter(resultado.anexos().getFirst().id()).isPresent());
        assertEquals("link", resultado.anexos().getLast().tipo());
        assertEquals("1234", resultado.confirmacao().codigo());
        assertFalse(json.writeValueAsString(registro.historico(42)).contains("resultado interno"));
        var sistemas = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), sistemas.capture(),
                any(ArrayNode.class), any(ArrayNode.class));
        assertSame(sistemas.getAllValues().get(0), sistemas.getAllValues().get(1));
    }
}
