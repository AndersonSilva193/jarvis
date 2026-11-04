package dev.jarvis.tarefas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import dev.jarvis.agente.Freio;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.autonomia.*;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.*;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.diario.*;
import dev.jarvis.ferramentas.*;
import dev.jarvis.ia.*;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.pc.*;
import dev.jarvis.proativo.*;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static dev.jarvis.tarefas.TarefaDeFundo.Status.*;

/** Relógio de negócio controlado; latches verificam concorrência sem rede nem esperas de minutos. */
class TarefasDeFundoTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final RelogioFalso relogio = new RelogioFalso("2026-11-03T15:00:00Z");
    final JarvisProperties props = mock(JarvisProperties.class);
    final ClaudeClient claude = mock(ClaudeClient.class);
    final SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
    final TelegramClient telegram = mock(TelegramClient.class);
    final AvisosProativos avisos = mock(AvisosProativos.class);
    final MedidorDeCusto medidor = mock(MedidorDeCusto.class);
    final RegistroConversas conversas = mock(RegistroConversas.class);
    final PresencaDaHud hud = mock(PresencaDaHud.class);
    final AnexosService anexos = new AnexosService();
    final ResultadosTardios tardios = new ResultadosTardios();
    final PcService pc = mock(PcService.class);
    final OperacoesPcService operacoes = mock(OperacoesPcService.class);
    final SitesService sites = mock(SitesService.class);
    EsperasDaTarefa esperas;
    DiarioDeAcoes diario;
    Concessoes concessoes;
    PoliticaDeAutonomia politica;
    Aprovacoes aprovacoes;
    Freio freio;
    Ferramentas ferramentas;
    TarefasDeFundo tarefas;

    @BeforeEach void preparar() throws Exception {
        when(props.zona()).thenReturn(java.time.ZoneId.of("America/Sao_Paulo"));
        when(props.tarefasSimultaneas()).thenReturn(2);
        when(props.tarefaMaxPassos()).thenReturn(30);
        when(props.tarefaMaxMinutos()).thenReturn(20);
        when(props.pastaDoDiario()).thenReturn(pasta);
        when(pc.online()).thenReturn(true);
        when(sites.sites()).thenReturn(Map.of("loja", "https://example.com"));
        // A forma do system precisa ser um array não vazio de blocos, como na API real.
        when(sistema.blocos(any(Canal.class), anyLong(), anyString(), nullable(ContextoArquivo.class)))
                .thenAnswer(i -> {
                    var blocos = json.createArrayNode(); blocos.addObject().put("type", "text").put("text", "fixo");
                    blocos.addObject().put("type", "text").put("text", "dinâmico"); return blocos;
                });
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class))).thenReturn(texto("Resultado pronto."));
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        concessoes = new Concessoes(json, relogio, props.zona(), pasta.resolve("concessoes.json"), diario);
        politica = new PoliticaDeAutonomia(pasta.resolve("autonomia.md"), concessoes);
        aprovacoes = new Aprovacoes(relogio, diario, politica);
        freio = new Freio(props, medidor, avisos, new FalasService(), json, relogio, pasta.resolve("freio.json"));
        @SuppressWarnings("unchecked") ObjectProvider<TarefasComputerUse> computer = mock(ObjectProvider.class);
        esperas = new EsperasDaTarefa(pc, operacoes, sites, computer, relogio);
    }
    void montar(Ferramenta... lista) {
        ferramentas = new Ferramentas(List.of(lista), json);
        tarefas = new TarefasDeFundo(new CicloDeFerramentas(ProvedorAnthropic.sobre(claude, json), ferramentas,
                sistema, BuscaWebServidor.desligada(), json), props, freio, politica, concessoes, aprovacoes,
                diario, telegram, conversas, anexos, hud, avisos, tardios, esperas, json, relogio, pasta.resolve("tarefas.json"));
    }
    @AfterEach void fechar() {
        if (tarefas != null) tarefas.fechar();
        esperas.fechar(); diario.fechar();
    }
    ObjectNode texto(String texto) {
        var n = json.createObjectNode().put("stop_reason", "end_turn");
        n.putArray("content").addObject().put("type", "text").put("text", texto); return n;
    }
    ObjectNode chamada(String... nomes) {
        var n = json.createObjectNode().put("stop_reason", "tool_use"); var c = n.putArray("content");
        for (String nome : nomes) c.addObject().put("type", "tool_use").put("id", java.util.UUID.randomUUID().toString())
                .put("name", nome).set("input", json.createObjectNode());
        return n;
    }
    String iniciar(List<TarefaDeFundo.AcaoPrevista> acoes) {
        tarefas.iniciar("organizar as notas", List.of("Consultar", "Organizar"), acoes, 42, Canal.TELEGRAM, 42,
                TarefaDeFundo.Origem.PEDIDO);
        return tarefas.listar(42).getLast().id();
    }
    void esperar(BooleanSupplier condicao) {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            while (!condicao.getAsBoolean()) { tarefas.varrer(); java.util.concurrent.locks.LockSupport.parkNanos(1_000_000); }
        });
    }
    void terminou(String id) { esperar(() -> List.of(CONCLUIDA, FALHOU, CANCELADA).contains(tarefas.estado(id, 42).status())); }
    Ferramenta ferramenta(String nome, java.util.concurrent.Callable<String> executar) {
        return new Ferramenta() {
            @Override public String nome() { return nome; }
            @Override public String descricao() { return nome; }
            @Override public ObjectNode esquemaEntrada() { return json.createObjectNode().put("type", "object"); }
            @Override public String executar(JsonNode entrada) throws Exception { return executar.call(); }
        };
    }
    Ferramenta sensivel(AtomicInteger executadas) {
        return new Ferramenta() {
            @Override public String nome() { return "sensivel"; }
            @Override public String descricao() { return "ação sensível de teste"; }
            @Override public ObjectNode esquemaEntrada() { return json.createObjectNode().put("type", "object"); }
            @Override public String executar(JsonNode entrada) { return "contexto necessário"; }
            @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
                return ResultadoFerramenta.de(aprovacoes.encaminhar(AcaoAutonoma.ROTINA_RODAR, "backup", "rotina",
                        "Executar backup", "Vou rodar backup. Posso?", Aprovacoes.Risco.COMUM,
                        contexto.usuarioAutenticado(), contexto.idPedido(), contexto.canal(), Aprovacoes.frase(() -> {
                            assertTrue(Thread.currentThread().getName().startsWith("tarefa-de-fundo-"));
                            executadas.incrementAndGet(); return "backup concluído";
                        })));
            }
        };
    }

    @Test void planoSemPerguntaIniciaDiretoEPersisteSomenteRetrato() throws Exception {
        montar(); var resposta = tarefas.iniciar("pesquisar", List.of("Consultar"), List.of(), 42, Canal.TELEGRAM, 42,
                TarefaDeFundo.Origem.PEDIDO);
        assertEquals(TarefasDeFundo.COMECO, resposta.texto()); assertTrue(resposta.encerrarTurno());
        String id = tarefas.listar(42).getFirst().id(); terminou(id);
        assertEquals(CONCLUIDA, tarefas.estado(id, 42).status());
        assertTrue(aprovacoes.pendenciaDe(42, "nova").isEmpty());
        esperar(() -> mockingDetails(telegram).getInvocations().size() > 0);
        verify(telegram).enviarMensagem(42, "Concluída: Resultado pronto.");
        assertEquals(1, tardios.drenar(42).size());
        String salvo = Files.readString(pasta.resolve("tarefas.json"));
        assertFalse(salvo.contains("tool_use")); assertFalse(salvo.contains("messages"));
        assertTrue(Files.exists(pasta.resolve("tarefas.json.bak")));
    }

    @Test void conversaRespondeEnquantoTarefaChamaModelo() throws Exception {
        var entrou = new CountDownLatch(1); var liberar = new CountDownLatch(1);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> {
                    if (i.getArgument(0) == Origem.TAREFA) { entrou.countDown(); assertTrue(liberar.await(4, TimeUnit.SECONDS)); }
                    return texto(i.getArgument(0) == Origem.TAREFA ? "final" : "Conversa livre.");
                });
        montar(); String id = iniciar(List.of());
        try {
            assertTrue(entrou.await(2, TimeUnit.SECONDS));
            var assistente = new Assistente(claude, ferramentas, sistema, json);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertEquals("Conversa livre.", assistente.responder(42, "oi")));
            assertEquals(RODANDO, tarefas.estado(id, 42).status());
        } finally { liberar.countDown(); }
        terminou(id);
    }

    @Test void maximoDeDuasExecucoesAtivas() throws Exception {
        var duas = new CountDownLatch(2); var liberar = new CountDownLatch(1); var entradas = new AtomicInteger();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> { entradas.incrementAndGet(); duas.countDown(); liberar.await(4, TimeUnit.SECONDS); return texto("feito"); });
        montar(); iniciar(List.of()); iniciar(List.of()); String terceiro = iniciar(List.of());
        try { assertTrue(duas.await(2, TimeUnit.SECONDS)); assertEquals(2, entradas.get()); assertEquals(NA_FILA, tarefas.estado(terceiro, 42).status()); }
        finally { liberar.countDown(); }
        terminou(terceiro);
    }

    @Test void planoTemUmaPerguntaEConcessoesSoParaSuaTarefa() throws Exception {
        var executadas = new AtomicInteger(); montar(sensivel(executadas));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("sensivel"), texto("feito"));
        String id = iniciar(List.of(new TarefaDeFundo.AcaoPrevista("rotina.rodar", "backup"),
                new TarefaDeFundo.AcaoPrevista("pc.reiniciar", null)));
        assertEquals(AGUARDANDO_APROVACAO, tarefas.estado(id, 42).status());
        String pergunta = aprovacoes.pendenciaDe(42, "nova").orElseThrow();
        assertTrue(pergunta.contains("backup")); assertTrue(pergunta.contains("reiniciar"));
        assertEquals(0, executadas.get()); verifyNoInteractions(claude);
        assertTrue(aprovacoes.executarPendente(42, "sim").isPresent());
        assertEquals(0, executadas.get(), "o sim não executa a ação na thread da conversa");
        terminou(id); assertEquals(1, executadas.get());
        assertTrue(aprovacoes.pendenciaDe(42, "outra").isEmpty());
        assertTrue(concessoes.vigente(AcaoAutonoma.ROTINA_RODAR, "backup").isEmpty());
        assertTrue(concessoes.ativas().isEmpty(), "fim invalida os recibos de tarefa");
    }

    @Test void planoCriticoExpiraEmDoisMinutos() throws Exception {
        montar(); String id = iniciar(List.of(new TarefaDeFundo.AcaoPrevista("pc.desligar", null)));
        relogio.avancar(Duration.ofMinutes(2)); tarefas.varrer();
        assertEquals(CANCELADA, tarefas.estado(id, 42).status()); verifyNoInteractions(claude);
    }

    @Test void proibidaRecusaPlanoInteiroSemCriarTarefa() throws Exception {
        Files.writeString(pasta.resolve("autonomia.md"), "## proibido\n- rotina.rodar:backup\n");
        montar(); var resultado = tarefas.iniciar("organizar", List.of("Ler", "Rodar"),
                List.of(new TarefaDeFundo.AcaoPrevista("rotina.rodar", "backup")), 42, Canal.TELEGRAM, 42, TarefaDeFundo.Origem.PEDIDO);
        assertTrue(resultado.texto().contains("backup")); assertTrue(tarefas.listar(42).isEmpty()); verifyNoInteractions(claude);
    }

    @Test void acaoNaoPrevistaEsperaVezEExecutaUmaVezNaThreadDaTarefa() throws Exception {
        var executadas = new AtomicInteger(); montar(sensivel(executadas));
        aprovacoes.pedir("rotina", "outra rotina", "Posso fazer a outra?", Aprovacoes.Risco.COMUM, 42, "antigo", () -> "feito");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("sensivel"), texto("final"));
        String id = iniciar(List.of()); esperar(() -> tarefas.estado(id, 42).status() == ESPERANDO_VOCE);
        assertEquals("Posso fazer a outra?", aprovacoes.pendenciaDe(42, "nova").orElseThrow());
        for (int i = 0; i < 10; i++) tarefas.varrer();
        verify(claude, times(1)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
        relogio.avancar(Duration.ofMinutes(30)); tarefas.varrer();
        assertTrue(aprovacoes.pendenciaDe(42, "nova").orElseThrow().contains("backup"));
        aprovacoes.executarPendente(42, "pode"); assertEquals(0, executadas.get());
        terminou(id); assertEquals(CONCLUIDA, tarefas.estado(id, 42).status()); assertEquals(1, executadas.get());
        assertEquals(0, tarefas.estado(id, 42).tempoAtivoMillis(), "a espera de trinta minutos não consome tempo ativo");
        verify(sistema, times(1)).blocos(eq(Canal.TAREFA), anyLong(), anyString(), nullable(ContextoArquivo.class));
    }

    @Test void limiteDePassosFalhaComMotivo() throws Exception {
        when(props.tarefaMaxPassos()).thenReturn(2); var chamadas = new AtomicInteger();
        montar(ferramenta("consulta", () -> "valor " + chamadas.incrementAndGet()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> chamada("consulta"));
        String id = iniciar(List.of()); terminou(id);
        assertEquals(FALHOU, tarefas.estado(id, 42).status()); assertEquals(2, tarefas.estado(id, 42).passos());
        assertTrue(tarefas.estado(id, 42).resultado().contains("passos"));
    }

    @Test void limiteDeTempoInterrompeChamadaEmAndamento() throws Exception {
        var entrou = new CountDownLatch(1); var bloquear = new CountDownLatch(1);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> { entrou.countDown(); bloquear.await(4, TimeUnit.SECONDS); return texto("tarde"); });
        montar(); String id = iniciar(List.of());
        try { assertTrue(entrou.await(2, TimeUnit.SECONDS)); relogio.avancar(Duration.ofMinutes(20)); tarefas.varrer(); terminou(id); }
        finally { bloquear.countDown(); }
        assertEquals(FALHOU, tarefas.estado(id, 42).status()); assertTrue(tarefas.estado(id, 42).resultado().contains("minutos"));
    }

    @Test void freioPausaERetomaSemReexecutarFerramentaJaConcluida() throws Exception {
        var feitas = new AtomicInteger(); var segundaChamada = new CountDownLatch(1); var bloquear = new CountDownLatch(1);
        var chamadas = new AtomicInteger(); montar(ferramenta("consulta", () -> { feitas.incrementAndGet(); return "resultado útil"; }));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> {
                    int n = chamadas.incrementAndGet(); if (n == 1) return chamada("consulta");
                    if (n == 2) { segundaChamada.countDown(); bloquear.await(4, TimeUnit.SECONDS); }
                    return texto("pronto");
                });
        String id = iniciar(List.of());
        try { assertTrue(segundaChamada.await(2, TimeUnit.SECONDS)); freio.pausar("telegram"); esperar(() -> tarefas.estado(id, 42).status() == PAUSADA); }
        finally { bloquear.countDown(); }
        esperar(() -> chamadas.get() == 2); freio.retomar("telegram"); terminou(id);
        assertEquals(CONCLUIDA, tarefas.estado(id, 42).status()); assertEquals(1, feitas.get());
    }

    @Test void reinicioConservaResumoEOrcamento() throws Exception {
        var salva = new TarefaDeFundo("abc12345", 42, Canal.TELEGRAM, 42, "continuar", List.of("Conferir"), List.of(),
                TarefaDeFundo.Origem.PEDIDO, relogio.instant());
        salva.status = RODANDO; salva.planoAprovado = true; salva.passos = 7; salva.tempoAtivoMillis = 1234;
        salva.resumo.add("consulta: arquivo já criado");
        new TarefasEmArquivo(pasta.resolve("tarefas.json"), json).salvar(1, List.of(salva.retrato(relogio.instant())));
        montar(); assertEquals(NA_FILA, tarefas.estado(salva.id, 42).status()); tarefas.varrer(); terminou(salva.id);
        var mensagens = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(eq(Origem.TAREFA), any(PerfilDeEsforco.class), any(ArrayNode.class), mensagens.capture(), any(ArrayNode.class));
        assertTrue(mensagens.getValue().toString().contains("Retomada após reinício"));
        assertTrue(mensagens.getValue().toString().contains("arquivo já criado"));
        assertEquals(8, tarefas.estado(salva.id, 42).passos()); assertEquals(1234, tarefas.estado(salva.id, 42).tempoAtivoMillis());
    }

    @Test void hudDesconectadaCaiNoTelegramEGatilhoPassaPeloProativo() throws Exception {
        montar(); tarefas.iniciar("resultado pedido", List.of("Consultar"), List.of(), 42, Canal.HUD, 42, TarefaDeFundo.Origem.PEDIDO);
        String id = tarefas.listar(42).getLast().id(); terminou(id);
        verify(telegram, timeout(1000)).enviarMensagem(42, "Concluída: Resultado pronto.");
        verifyNoInteractions(avisos);
        tarefas.iniciar("resultado autônomo", List.of("Consultar"), List.of(), 42, Canal.HUD, 42, TarefaDeFundo.Origem.GATILHO);
        String gatilho = tarefas.listar(42).getLast().id(); terminou(gatilho);
        verify(avisos, timeout(1000)).avisar(eq("tarefa:" + gatilho), eq("CONCLUIDA"), startsWith("Concluída:"), isNull());
        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());
    }

    @Test void isolamentoDasUrlsEntreTarefasEConversa() {
        var urls = new dev.jarvis.busca.UrlsDaBusca();
        urls.registrar("conversa", List.of("https://conversa"));
        var a = new ContextoDaTarefa.Dados(); var b = new ContextoDaTarefa.Dados();
        try (var escopo = ContextoDaTarefa.entrar("a", 42, v -> { }, s -> { }, a)) { urls.registrar("a", List.of("https://a")); }
        try (var escopo = ContextoDaTarefa.entrar("b", 42, v -> { }, s -> { }, b)) {
            assertFalse(urls.apareceu("b", "https://a")); urls.registrar("b", List.of("https://b"));
        }
        try (var escopo = ContextoDaTarefa.entrar("a", 42, v -> { }, s -> { }, a)) { assertTrue(urls.apareceu("a", "https://a")); }
        assertTrue(urls.apareceu("conversa", "https://conversa")); assertFalse(urls.apareceu("conversa", "https://a"));
    }

    @Test void suspensaoNoMeioDoLoteNaoRepeteAcaoAnterior() throws Exception {
        var primeira = new AtomicInteger(); var ultima = new AtomicInteger(); var sensiveis = new AtomicInteger();
        montar(ferramenta("primeira", () -> "feita " + primeira.incrementAndGet()), sensivel(sensiveis),
                ferramenta("ultima", () -> "feita " + ultima.incrementAndGet()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("primeira", "sensivel", "ultima"), texto("feito"));
        String id = iniciar(List.of()); esperar(() -> tarefas.estado(id, 42).status() == ESPERANDO_VOCE);
        esperar(() -> aprovacoes.pendenciaDe(42, "nova").isPresent());
        assertEquals(1, primeira.get()); assertEquals(0, ultima.get());
        aprovacoes.executarPendente(42, "sim"); terminou(id);
        assertEquals(1, primeira.get()); assertEquals(1, ultima.get()); assertEquals(1, sensiveis.get());
        var enviados = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), enviados.capture(), any(ArrayNode.class), any(ArrayNode.class));
        assertSame(enviados.getAllValues().getFirst(), enviados.getAllValues().getLast());
    }

    @Test void esperaLiberaUnicoTrabalhadorParaOutraTarefa() throws Exception {
        when(props.tarefasSimultaneas()).thenReturn(1); montar(sensivel(new AtomicInteger()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("sensivel"), texto("outra terminou"), texto("primeira terminou"));
        String primeira = iniciar(List.of()); esperar(() -> tarefas.estado(primeira, 42).status() == ESPERANDO_VOCE);
        String segunda = iniciar(List.of()); terminou(segunda);
        assertEquals(CONCLUIDA, tarefas.estado(segunda, 42).status());
        assertEquals(ESPERANDO_VOCE, tarefas.estado(primeira, 42).status());
        esperar(() -> aprovacoes.pendenciaDe(42, "nova").isPresent()); aprovacoes.executarPendente(42, "sim"); terminou(primeira);
    }

    @Test void pcOfflineEsperaSemRepetirChamadaAoModelo() throws Exception {
        when(pc.online()).thenReturn(false); var executadas = new AtomicInteger();
        montar(ferramenta("listar_arquivos", () -> "lista " + executadas.incrementAndGet()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("listar_arquivos"), texto("feito"));
        String id = iniciar(List.of()); esperar(() -> tarefas.estado(id, 42).status() == ESPERANDO_PC);
        relogio.avancar(Duration.ofHours(1)); for (int i = 0; i < 5; i++) tarefas.varrer();
        assertEquals(0, executadas.get());
        verify(claude, times(1)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
        when(pc.online()).thenReturn(true); terminou(id); assertEquals(1, executadas.get());
        assertEquals(CONCLUIDA, tarefas.estado(id, 42).status());
    }

    @Test void horarioSuspendeSemConsumirTempoNemPassos() throws Exception {
        montar(new AguardarTarefaFerramenta(esperas, json));
        var chamada = chamada("aguardar_tarefa");
        ((ObjectNode) chamada.path("content").get(0).path("input")).put("condicao", "horario")
                .put("ate", relogio.instant().plus(Duration.ofHours(2)).toString());
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada, texto("feito no horário"));
        String id = iniciar(List.of()); esperar(() -> tarefas.estado(id, 42).esperaTipo().equals("HORARIO"));
        relogio.avancar(Duration.ofMinutes(90)); tarefas.varrer(); assertEquals(1, tarefas.estado(id, 42).passos());
        relogio.avancar(Duration.ofMinutes(30)); terminou(id);
        assertEquals(CONCLUIDA, tarefas.estado(id, 42).status()); assertEquals(2, tarefas.estado(id, 42).passos());
        assertEquals(0, tarefas.estado(id, 42).tempoAtivoMillis());
    }

    @Test void recusaDaAcaoImprevistaCancelaSemExecutar() throws Exception {
        var executadas = new AtomicInteger(); montar(sensivel(executadas));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("sensivel"));
        String id = iniciar(List.of()); esperar(() -> aprovacoes.pendenciaDe(42, "nova").isPresent());
        aprovacoes.recusarPendente(42, "não"); terminou(id);
        assertEquals(CANCELADA, tarefas.estado(id, 42).status()); assertEquals(0, executadas.get());
    }

    @Test void tetoDiarioPausaEmVezDeFalhar() throws Exception {
        when(props.tetoAutonomoDeCusto()).thenReturn(1.0);
        montar(new AguardarTarefaFerramenta(esperas, json));
        var chamada = chamada("aguardar_tarefa"); ((ObjectNode) chamada.path("content").get(0).path("input"))
                .put("condicao", "horario").put("ate", relogio.instant().plusSeconds(60).toString());
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada, texto("feito"));
        String id = iniciar(List.of()); esperar(() -> tarefas.estado(id, 42).esperaTipo().equals("HORARIO"));
        when(medidor.gastoAutonomoDeHoje()).thenReturn(1.0); freio.avaliarCusto(); tarefas.varrer();
        assertEquals(PAUSADA, tarefas.estado(id, 42).status());
        freio.retomar("telegram"); assertFalse(freio.podeAgirSozinho("tarefa"));
        assertEquals(PAUSADA, tarefas.estado(id, 42).status());
    }

    @Test void repeticaoTambemValeEmTarefa() throws Exception {
        var executadas = new AtomicInteger(); montar(ferramenta("consulta", () -> "resultado " + executadas.incrementAndGet()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> chamada("consulta"));
        String id = iniciar(List.of()); terminou(id);
        assertEquals(2, executadas.get()); assertEquals(FALHOU, tarefas.estado(id, 42).status());
        assertTrue(tarefas.estado(id, 42).resultado().contains("repetindo"));
    }

    @Test void diarioPersisteOrigemComIdDaTarefaSemDuplicarEfeito() throws Exception {
        montar(ferramenta("acao", () -> {
            diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei real.txt", OrigemDaAcao.TELEGRAM, 42, null);
            return "real.txt criado";
        }));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("acao"), texto("feito"));
        String id = iniciar(List.of()); terminou(id); diario.aguardar();
        var acoes = diario.deHoje().acoes(); assertEquals(1, acoes.size());
        assertEquals("tarefa:" + id, acoes.getFirst().origemCompleta());
        assertEquals(TipoDeAcao.CRIAR_ARQUIVO, acoes.getFirst().tipo());
        var relido = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        try { assertEquals("tarefa:" + id, relido.deHoje().acoes().getFirst().origemCompleta()); }
        finally { relido.fechar(); }
    }

    @Test void anexoDaTarefaChegaAoTelegram() throws Exception {
        Path arquivo = pasta.resolve("resultado.txt"); Files.writeString(arquivo, "resultado real");
        Ferramenta gera = new Ferramenta() {
            @Override public String nome() { return "gera"; }
            @Override public String descricao() { return "gera artefato de teste"; }
            @Override public ObjectNode esquemaEntrada() { return json.createObjectNode().put("type", "object"); }
            @Override public String executar(JsonNode entrada) { return "arquivo criado"; }
            @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
                return new ResultadoFerramenta("arquivo criado", List.of(new ResultadoFerramenta.Artefato(arquivo,
                        "resultado.txt", "text/plain")), List.of(), null, List.of(), List.of());
            }
        };
        montar(gera);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("gera"), texto("feito"));
        String id = iniciar(List.of()); terminou(id);
        verify(telegram, timeout(1000)).enviarDocumento(eq(42L), aryEq("resultado real".getBytes(java.nio.charset.StandardCharsets.UTF_8)), eq("resultado.txt"));
    }

    @Test void reinicioRecuperaBackupSemReaproveitarAprovacaoPerdida() throws Exception {
        var salva = new TarefaDeFundo("abc12345", 42, Canal.TELEGRAM, 42, "aprovar de novo", List.of("Rodar"),
                List.of(new TarefaDeFundo.AcaoPrevista("rotina.rodar", "backup")), TarefaDeFundo.Origem.PEDIDO, relogio.instant());
        salva.status = AGUARDANDO_APROVACAO;
        var arquivo = new TarefasEmArquivo(pasta.resolve("tarefas.json"), json);
        arquivo.salvar(1, List.of(salva.retrato(relogio.instant()))); arquivo.salvar(2, List.of(salva.retrato(relogio.instant())));
        Files.writeString(pasta.resolve("tarefas.json"), "corrompido");
        montar(); tarefas.varrer();
        assertEquals(AGUARDANDO_APROVACAO, tarefas.estado(salva.id, 42).status());
        esperar(() -> aprovacoes.pendenciaDe(42, "nova").isPresent()); verifyNoInteractions(claude);
    }

    @Test void cancelarInterrompeModeloESuprimeFerramentasSeguintes() throws Exception {
        var entrou = new CountDownLatch(1); var bloquear = new CountDownLatch(1); var executadas = new AtomicInteger();
        montar(ferramenta("efeito", () -> "feito " + executadas.incrementAndGet()));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> { entrou.countDown(); bloquear.await(4, TimeUnit.SECONDS); return chamada("efeito"); });
        String id = iniciar(List.of());
        try { assertTrue(entrou.await(2, TimeUnit.SECONDS)); assertTrue(tarefas.cancelarUltima(42)); terminou(id); }
        finally { bloquear.countDown(); }
        assertEquals(CANCELADA, tarefas.estado(id, 42).status()); assertEquals(0, executadas.get());
        assertFalse(tarefas.cancelar(id, 7)); assertNull(tarefas.estado(id, 7));
    }

    @Test void ferramentaDeInicioEncerraConversaSemOutraChamadaAoModelo() throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<TarefasDeFundo> provider = mock(ObjectProvider.class);
        var iniciar = new IniciarTarefaDeFundoFerramenta(provider, json);
        montar(iniciar); when(provider.getObject()).thenReturn(tarefas);
        var delegar = chamada(iniciar.nome()); var entrada = (ObjectNode) delegar.path("content").get(0).path("input");
        entrada.put("objetivo", "organizar"); entrada.putArray("plano").add("Consultar"); entrada.putArray("acoes_previstas");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenAnswer(i -> i.getArgument(0) == Origem.CONVERSA ? delegar : texto("final"));
        var assistente = new Assistente(claude, ferramentas, sistema, json);
        var resposta = assistente.responder(42, "faça o trabalho longo", Canal.TELEGRAM, new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido-delegado"; }
        });
        assertEquals(TarefasDeFundo.COMECO, resposta.texto());
        verify(claude, times(1)).criarMensagem(eq(Origem.CONVERSA), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
        assertEquals(42, tarefas.listar(42).getFirst().chat());
        terminou(tarefas.listar(42).getFirst().id());
        assertFalse(ferramentas.definicoes(Canal.TAREFA).toString().contains("iniciar_tarefa_de_fundo"));
    }

    @Test void operacaoIncertaAposReinicioNaoEsperaUmResultadoQueNuncaVira() throws Exception {
        String id = java.util.UUID.randomUUID().toString();
        when(operacoes.chatId(id)).thenReturn(42L);
        when(operacoes.estado(id)).thenReturn(java.util.Optional.of(new OperacoesPcService.Retrato(
                id, "rodar_rotina", "resultado_incerto", "", "", "", "reinício durante execução", null, false)));
        var espera = esperas.criar(SuspensaoDaTarefa.Tipo.OPERACAO_PC, id, null, 42);
        assertTrue(espera.pronta()); assertTrue(espera.continuar().texto().contains("confira o estado"));
    }

    @Test void reinicioReapresentaAprovacaoImprevistaMesmoSeTarefaEstavaPausada() throws Exception {
        var salva = new TarefaDeFundo("abc12345", 42, Canal.TELEGRAM, 42, "organizar notas", List.of("Rodar backup"),
                List.of(), TarefaDeFundo.Origem.PEDIDO, relogio.instant());
        salva.status = PAUSADA; salva.planoAprovado = true; salva.esperaTipo = "APROVACAO";
        new TarefasEmArquivo(pasta.resolve("tarefas.json"), json).salvar(1, List.of(salva.retrato(relogio.instant())));
        var executadas = new AtomicInteger(); montar(sensivel(executadas));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("sensivel"), texto("feito"));
        tarefas.varrer(); esperar(() -> aprovacoes.pendenciaDe(42, "nova").isPresent());
        assertEquals(ESPERANDO_VOCE, tarefas.estado(salva.id, 42).status()); assertEquals(0, executadas.get());
        aprovacoes.executarPendente(42, "sim"); terminou(salva.id); assertEquals(1, executadas.get());
    }
}
