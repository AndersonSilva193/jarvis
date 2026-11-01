package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.*;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.*;
import dev.jarvis.pc.*;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Modelo e transporte dublados; seleção, ferramentas, laço e persistência são os reais. */
class ContinuidadeEdicaoTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String OUTRO = "33333333-3333-3333-3333-333333333333";
    private static final String EDIT = "22222222-2222-2222-2222-222222222222";
    private static final String HASH = "a".repeat(64);
    private final JsonMapper json = JsonMapper.builder().build();
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final PcService pc = mock(PcService.class);
    private final OperacoesPcService operacoes = mock(OperacoesPcService.class);
    private final AtomicReference<String> versao = new AtomicReference<>(HASH);
    private final AtomicInteger aplicacoes = new AtomicInteger();
    private final List<ComandoPc> consultas = new ArrayList<>();
    private final List<HistoricoEmArquivo> abertos = new ArrayList<>();
    private Ferramentas ferramentas;
    private RegistroConversas registro;
    @TempDir Path pasta;

    private JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("teste", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }

    private ObjectNode metadados(String id) {
        return json.createObjectNode().put("id", id).put("nome", ID.equals(id) ? "gastos.xlsx" : "outro.xlsx")
                .put("formato", "xlsx").put("versaoEsperada", versao.get());
    }

    @BeforeEach void preparar() throws Exception {
        var arquivos = mock(ArquivosPcService.class);
        var telegram = mock(TelegramClient.class);
        Path anexo = pasta.resolve("gastos.xlsx");
        Files.writeString(anexo, "bytes de teste transportados pelo agente");
        when(arquivos.obter(anyString())).thenReturn(Optional.of(anexo));
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc comando = chamada.getArgument(0);
            consultas.add(comando);
            JsonNode dados = null;
            if ("listar_arquivos".equals(comando.acao())) {
                ObjectNode lista = json.createObjectNode().put("total", 1).put("pagina", 1)
                        .put("tamanhoPagina", 50).put("temMais", false);
                lista.putArray("arquivos").add(metadados(ID));
                dados = lista;
            } else if ("ler_arquivo".equals(comando.acao())) {
                dados = metadados(comando.dados().path("id").asText()).put("aba", "Gastos")
                        .put("proximaLinhaFisica", 2);
            }
            return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true,
                    "criar_arquivo".equals(comando.acao()) ? "gastos.xlsx" : "consulta concluída", null, null,
                    "criar_arquivo".equals(comando.acao()), dados));
        });
        when(operacoes.online()).thenReturn(true);
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc comando = chamada.getArgument(0);
            if ("preparar_edicao".equals(comando.acao())) {
                assertEquals(versao.get(), comando.dados().path("versaoEsperada").asText());
                return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true, "consulta concluída",
                        null, null, false, json.createObjectNode().put("editId", EDIT).put("nome", "gastos.xlsx")
                                .put("previa", "Gastos!B2: vazio → 6").put("apelidoUnico", true)));
            }
            assertEquals("aplicar_edicao", comando.acao());
            aplicacoes.incrementAndGet();
            versao.set("b".repeat(64));
            // Salvo, mas o upload falhou: a versão precisa avançar mesmo sem artefato.
            return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true, "gastos.xlsx",
                    null, null, false, json.createObjectNode().put("editId", EDIT).put("versao", versao.get())));
        });
        var fluxo = new FluxoEdicaoPc(operacoes, arquivos, telegram, json);
        ferramentas = new Ferramentas(List.of(new CriarArquivoFerramenta(pc, arquivos, telegram, json, props()),
                new ListarArquivosFerramenta(pc, json), new LerArquivoFerramenta(pc, json),
                new EditarArquivoFerramenta(fluxo, json)), json);
    }

    @AfterEach void fechar() { abertos.forEach(HistoricoEmArquivo::fechar); }

    private HistoricoEmArquivo store(Duration validade) {
        var arquivo = new HistoricoEmArquivo(pasta.resolve("historico"), validade, json);
        abertos.add(arquivo);
        return arquivo;
    }

    private Assistente assistente(HistoricoEmArquivo arquivo) {
        registro = new RegistroConversas(e -> { });
        return new Assistente(claude, ferramentas,
                new SistemaDoJarvis(new PersonalidadeService(pasta.resolve("sem-personalidade"), "Anderson"), json, props()),
                json, registro, new AnexosService(), BuscaWebServidor.desligada(),
                new Aprovacoes(), arquivo);
    }

    private OuvinteProgresso autenticado(long usuario) {
        String id = java.util.UUID.randomUUID().toString();
        return new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return usuario; }
            @Override public String idPedido() { return id; }
        };
    }

    private JsonNode chamada(String nome, JsonNode entrada) {
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "tool_use");
        resposta.putArray("content").addObject().put("type", "tool_use").put("id", "tool_1")
                .put("name", nome).set("input", entrada);
        return resposta;
    }

    private JsonNode resposta(String texto) {
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    private void modelo(JsonNode... respostas) throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostas[0], Arrays.copyOfRange(respostas, 1, respostas.length));
    }

    private JsonNode criar() throws Exception {
        return chamada("criar_arquivo", json.readTree("""
                {"nome":"gastos","formato":"xlsx","abas":[{"nome":"Gastos",
                "colunas":[{"nome":"Descrição"},{"nome":"Valor"}],"linhas":[]}]}
                """));
    }

    private JsonNode editar() {
        ObjectNode entrada = json.createObjectNode().put("destino", "original");
        entrada.putArray("operacoes").addObject().put("tipo", "preencher_celula_vazia")
                .put("aba", "Gastos").put("celula", "B2").put("tipoAnterior", "vazio")
                .putNull("valorAnterior").put("tipoNovo", "numero").put("valorNovo", 6);
        return chamada("editar_arquivo", entrada);
    }

    @Test void criacaoEsclarecimentoEValorCurtoEditamUmaVezSemIdNemVersao() throws Exception {
        var arquivo = store(Duration.ofHours(24));
        var assistente = assistente(arquivo);
        modelo(criar(), resposta("Criei gastos.xlsx."));
        assistente.responder(42, "crie a planilha de gastos", Canal.TELEGRAM, autenticado(42));
        modelo(resposta("O gasto da voz é R$ 35 ou R$ 6?"));
        assistente.responder(42, "coloca nessa planilha o gasto da voz, R$ 35 ou 6", Canal.TELEGRAM, autenticado(42));
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Incluí R$ 6."));
        assistente.responder(42, "R$ 6", Canal.TELEGRAM, autenticado(42));
        arquivo.aguardar();
        assertEquals(1, aplicacoes.get());
        assertEquals(ID, consultas.stream().filter(c -> c.acao().equals("ler_arquivo")).findFirst()
                .orElseThrow().dados().path("id").asText());
        var salvo = json.readTree(Files.readString(pasta.resolve("historico/chat-42.json")));
        assertEquals(versao.get(), salvo.path("contextosArquivos").get(0).path("alvo").path("versao").asText());
        ArgumentCaptor<ArrayNode> sistema = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), sistema.capture(),
                any(ArrayNode.class), any(ArrayNode.class));
        assertTrue(sistema.getValue().get(1).path("text").asText().contains("gastos.xlsx"));
        var chamadas = sistema.getAllValues();
        assertSame(chamadas.get(chamadas.size() - 3), chamadas.getLast(), "system não muda entre passos");
    }

    @Test void alvoSobreviveCompactacaoRestartEExpiracaoDosTurnosMasExigeReleitura() throws Exception {
        var arquivo = store(Duration.ofHours(24));
        var primeiro = assistente(arquivo);
        modelo(criar(), resposta("Criei."));
        primeiro.responder(42, "crie gastos.xlsx", Canal.TELEGRAM, autenticado(42));
        for (int i = 0; i < 12; i++) {
            modelo(resposta("Certo."));
            primeiro.responder(42, "outra conversa " + i, Canal.TELEGRAM, autenticado(42));
        }
        arquivo.aguardar();
        var carregador = store(Duration.ofNanos(1));
        assertTrue(carregador.carregarTudo().getFirst().turnos().isEmpty());
        var depois = assistente(carregador);
        depois.restaurarHistorico();
        modelo(editar(), resposta("Preciso reler."));
        depois.responder(42, "R$ 6", Canal.TELEGRAM, autenticado(42));
        assertEquals(0, aplicacoes.get());
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Atualizei."));
        depois.responder(42, "pode reler e incluir os R$ 6", Canal.TELEGRAM, autenticado(42));
        assertEquals(1, aplicacoes.get());
        depois.limparHistorico(42);
        modelo(editar(), resposta("Selecione o arquivo."));
        depois.responder(42, "R$ 6", Canal.TELEGRAM, autenticado(42));
        assertEquals(1, aplicacoes.get());
        carregador.aguardar();
        assertTrue(carregador.carregarTudo().isEmpty());
    }

    @Test void contextoNaoVazaEntreUsuarioChatOuCanalENaoVoltaAposAmbiguidade() throws Exception {
        var primeiro = assistente(store(Duration.ofHours(24)));
        modelo(criar(), resposta("Criei."));
        primeiro.responder(42, "crie gastos.xlsx", Canal.TELEGRAM, autenticado(42));
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Falta selecionar."));
        primeiro.responder(42, "R$ 6", Canal.TELEGRAM, autenticado(99));
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Falta selecionar."));
        primeiro.responder(99, "R$ 6", Canal.TELEGRAM, autenticado(42));
        var reservado = registro.reservar(42, "R$ 6");
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Falta selecionar."));
        primeiro.responder(42, "R$ 6", Canal.HUD, new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return reservado.id(); }
        });
        assertEquals(0, aplicacoes.get());
        var ambiguos = json.createObjectNode().put("total", 2).put("pagina", 1).put("tamanhoPagina", 50);
        ambiguos.putArray("arquivos").add(metadados(ID)).add(metadados(OUTRO));
        doAnswer(chamada -> {
            ComandoPc comando = chamada.getArgument(0);
            return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true, "consulta concluída",
                    null, null, false, ambiguos));
        }).when(pc).enfileirar(any(), anyString(), anyLong());
        modelo(chamada("listar_arquivos", json.createObjectNode().put("nome", "gastos")),
                chamada("ler_arquivo", json.createObjectNode().put("id", ID)), editar(), resposta("Qual arquivo?"));
        primeiro.responder(42, "inclua no arquivo de gastos", Canal.TELEGRAM, autenticado(42));
        assertEquals(0, aplicacoes.get());
    }

    @Test void falhaDoModeloAposCriacaoNaoPerdeAlvoNemGravaTurnoIncompleto() throws Exception {
        var arquivo = store(Duration.ofHours(24));
        var primeiro = assistente(arquivo);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(criar()).thenThrow(new java.io.IOException("modelo caiu"));
        assertThrows(java.io.IOException.class, () -> primeiro.responder(42, "crie a planilha", Canal.TELEGRAM, autenticado(42)));
        arquivo.aguardar();
        var salvo = arquivo.carregarTudo().getFirst();
        assertTrue(salvo.turnos().isEmpty());
        assertEquals(ID, salvo.contextosArquivos().getFirst().alvo().id());
    }

    @Test void criacaoDeOutroArquivoQueFalhouNaoReutilizaOAnterior() throws Exception {
        var primeiro = assistente(store(Duration.ofHours(24)));
        modelo(criar(), resposta("Criei."));
        primeiro.responder(42, "crie gastos.xlsx", Canal.TELEGRAM, autenticado(42));
        doAnswer(chamada -> CompletableFuture.failedFuture(new java.io.IOException("conexão caiu")))
                .when(pc).enfileirar(any(), anyString(), anyLong());
        JsonNode outra = criar();
        ((ObjectNode) outra.path("content").get(0).path("input")).put("nome", "outra");
        modelo(outra, resposta("Não consegui criar."));
        primeiro.responder(42, "crie outra planilha", Canal.TELEGRAM, autenticado(42));
        modelo(chamada("ler_arquivo", json.createObjectNode()), editar(), resposta("Preciso selecionar o arquivo."));
        primeiro.responder(42, "coloque R$ 6 nela", Canal.TELEGRAM, autenticado(42));
        assertEquals(0, aplicacoes.get());
    }
}
