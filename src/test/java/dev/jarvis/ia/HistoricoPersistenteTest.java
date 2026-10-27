package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.custo.Origem;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.HistoricoEmArquivo;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O histórico em disco: o que volta depois de um restart e, mais importante, o que nunca chega lá.
 *
 * Cada caso monta um "primeiro Assistente", deixa um turno acontecer e constrói um <b>segundo</b>
 * apontando para a mesma pasta temporária — é essa troca que imita o container reiniciando.
 */
class HistoricoPersistenteTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path pasta;

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final List<HistoricoEmArquivo> abertos = new java.util.ArrayList<>();

    /**
     * Fecha as filas de gravação antes de o JUnit apagar a pasta: no Windows um arquivo sendo
     * escrito trava a remoção do diretório, e o teste quebraria no fim por um motivo que não é o
     * dele.
     */
    @org.junit.jupiter.api.AfterEach void fecharFilas() {
        abertos.forEach(HistoricoEmArquivo::fechar);
    }

    @Test void restartDevolveOContextoAoModeloEAsTrocasAHud() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("O servidor está de pé."));
        assistente(arquivo, new RegistroConversas(evento -> { }))
                .responder(42, "como está o servidor?", Canal.TELEGRAM);
        arquivo.aguardar();

        var conversasNovas = new RegistroConversas(evento -> { });
        HistoricoEmArquivo depois = store(Duration.ofHours(24));
        Assistente depoisDoRestart = assistente(depois, conversasNovas);
        depoisDoRestart.restaurarHistorico();

        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("Continua de pé."));
        depoisDoRestart.responder(42, "e agora?", Canal.TELEGRAM);
        depois.aguardar();

        String enviado = ultimasMensagens().toString();
        assertTrue(enviado.contains("como está o servidor?"), "o pedido antigo volta ao modelo");
        assertTrue(enviado.contains("O servidor está de pé."), "a resposta antiga volta ao modelo");
        // A HUD mostra a lista pública; a de antes do restart está lá, e "ultima" não, porque
        // histórico restaurado é para olhar, não para a tela falar de novo.
        var historico = conversasNovas.historico(42);
        assertEquals("como está o servidor?", historico.trocas().getFirst().pedido());
        assertEquals(null, historico.ultima());
    }

    @Test void turnoQueFalhouNaoChegaAoDisco() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenThrow(new IOException("a API caiu no meio"));
        assertThrows(IOException.class, () -> assistente(arquivo, new RegistroConversas(evento -> { }))
                .responder(42, "faça algo que vai falhar", Canal.TELEGRAM));
        arquivo.aguardar();
        assertFalse(Files.exists(pasta.resolve("chat-42.json")), "turno incompleto não é salvo");
    }

    @Test void esquecerAMemoriaApagaOsArquivosDeTodosOsChatsDoUsuario() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        var conversas = new RegistroConversas(evento -> { });
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("anotado"));
        Assistente assistente = assistente(arquivo, conversas, esquecedor());
        assistente.responder(42, "oi", Canal.TELEGRAM, autenticado("telegram-1", 42));
        assistente.responder(99, "oi daqui também", Canal.TELEGRAM, autenticado("telegram-2", 42));
        arquivo.aguardar();
        assertTrue(Files.exists(pasta.resolve("chat-42.json")));
        assertTrue(Files.exists(pasta.resolve("chat-99.json")));

        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("esquecer_tudo"));
        assistente.responder(42, "esqueça o que eu te contei", Canal.TELEGRAM,
                autenticado("telegram-3", 42));

        assertFalse(Files.exists(pasta.resolve("chat-42.json")), "o chat do pedido sai do disco");
        assertFalse(Files.exists(pasta.resolve("chat-99.json")), "o outro chat do usuário também");
        assertTrue(store(Duration.ofHours(24)).carregarTudo().isEmpty());
    }

    @Test void limparApagaOArquivoDaquelaConversa() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("certo"));
        Assistente assistente = assistente(arquivo, new RegistroConversas(evento -> { }));
        assistente.responder(42, "oi", Canal.TELEGRAM);
        arquivo.aguardar();
        assertTrue(Files.exists(pasta.resolve("chat-42.json")));

        assistente.limparHistorico(42);

        assertFalse(Files.exists(pasta.resolve("chat-42.json")));
        assertFalse(Files.exists(pasta.resolve("chat-42.json.bak")), "o .bak sai junto");
    }

    @Test void turnoMaisVelhoQueAValidadeNaoVolta() throws Exception {
        HistoricoEmArquivo gravador = store(Duration.ofHours(24));
        gravador.gravarAgora(42, 0, List.of(List.of(mensagem("user", "assunto de ontem"))), List.of());
        // A validade é lida na carga, então um store de validade curtíssima vê o mesmo arquivo
        // como vencido — é o que acontece com o turno de ontem na partida de hoje.
        assertTrue(store(Duration.ofHours(24)).carregarTudo().getFirst().turnos().size() == 1);
        assertTrue(store(Duration.ofNanos(1)).carregarTudo().isEmpty(), "turno vencido é descartado");
    }

    @Test void capturaDeTelaNaoVaiParaODisco() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        ObjectNode comImagem = JSON.createObjectNode().put("role", "user");
        ObjectNode resultado = comImagem.putArray("content").addObject();
        resultado.put("type", "tool_result").put("tool_use_id", "tu_1");
        ObjectNode imagem = resultado.putArray("content").addObject().put("type", "image");
        imagem.putObject("source").put("type", "base64").put("media_type", "image/png")
                .put("data", "iVBORw0KGgoAAAANSUhEUgAASEGREDODONO");
        arquivo.gravarAgora(42, 0, List.of(List.of(comImagem)), List.of());

        String salvo = Files.readString(pasta.resolve("chat-42.json"));
        assertFalse(salvo.contains("iVBORw0KGgoAAAANSUhEUgAASEGREDODONO"), "nada de base64 em disco");
        assertFalse(salvo.contains("\"image\""));
        assertTrue(salvo.contains("[imagem omitida]"));
    }

    @Test void dataUriDentroDeUmTextoTambemSai() throws Exception {
        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        arquivo.gravarAgora(42, 0, List.of(List.of(mensagem("user",
                "olha isso data:image/png;base64,AAAABBBBCCCCDDDD e me diga"))), List.of());
        String salvo = Files.readString(pasta.resolve("chat-42.json"));
        assertFalse(salvo.contains("AAAABBBBCCCCDDDD"));
        assertTrue(salvo.contains("[imagem omitida] e me diga"));
    }

    @Test void pastaDesligadaMantemOComportamentoAntigo() {
        HistoricoEmArquivo desligado = HistoricoEmArquivo.desligado();
        assertFalse(desligado.ligado());
        assertTrue(desligado.carregarTudo().isEmpty());
        // Nenhuma das operações pode estourar sem pasta: é o Jarvis rodando sem o volume.
        desligado.agendar(1, 1, List.of(), List.of());
        desligado.apagar(1);
        desligado.fechar();
    }

    /**
     * Histórico gravado por uma versão que guardava o turno inteiro: ao carregar, ele é compactado.
     *
     * <p>São dois turnos de propósito, porque na carga vale a mesma regra de sempre — o último
     * volta completo e os anteriores não. Com um turno só nada seria compactado, e o teste passaria
     * sem provar nada.
     */
    @Test void historicoAntigoEmDiscoEhCompactadoAoCarregar() throws Exception {
        String id = "3f9c1a20-4b7e-4c11-9d02-7a1e55c8b3df";
        String listagem = "{\"arquivos\":[{\"id\":\"" + id + "\",\"nome\":\"notas.md\"}]}";
        ObjectNode chamou = JSON.createObjectNode().put("role", "assistant");
        chamou.putArray("content").addObject().put("type", "tool_use").put("id", "tu_1")
                .put("name", "listar_arquivos").set("input", JSON.createObjectNode());
        ObjectNode devolveu = JSON.createObjectNode().put("role", "user");
        devolveu.putArray("content").addObject().put("type", "tool_result")
                .put("tool_use_id", "tu_1").put("content", listagem);
        ObjectNode respondeu = JSON.createObjectNode().put("role", "assistant");
        respondeu.putArray("content").addObject().put("type", "text").put("text", "Você tem notas.md.");
        List<ObjectNode> antigo = List.of(mensagem("user", "lista meus arquivos"), chamou, devolveu,
                respondeu);
        List<ObjectNode> recente = List.of(mensagem("user", "obrigado"), mensagem("assistant", "De nada."));

        HistoricoEmArquivo arquivo = store(Duration.ofHours(24));
        arquivo.gravarAgora(42, 42, List.of(antigo, recente), List.of());

        HistoricoEmArquivo depois = store(Duration.ofHours(24));
        Assistente depoisDoRestart = assistente(depois, new RegistroConversas(evento -> { }));
        depoisDoRestart.restaurarHistorico();
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta("Pois não, senhor."));
        depoisDoRestart.responder(42, "e agora?", Canal.TELEGRAM);

        String enviado = ultimasMensagens().toString();
        assertFalse(enviado.contains("tool_use"), "o turno antigo não reenvia a chamada de ferramenta");
        assertFalse(enviado.contains("tool_result"), "nem o resultado dela");
        assertTrue(enviado.contains("[ferramentas: listar_arquivos → notas.md (id " + id + ")]"),
                "o que sobra é a nota, com o identificador inteiro");
        assertTrue(enviado.contains("Você tem notas.md."), "a resposta antiga continua lá");
        assertTrue(enviado.contains("De nada."), "e o turno mais recente volta inteiro");
    }

    private HistoricoEmArquivo store(Duration validade) {
        HistoricoEmArquivo criado = new HistoricoEmArquivo(pasta, validade, JSON);
        abertos.add(criado);
        return criado;
    }

    private Assistente assistente(HistoricoEmArquivo arquivo, RegistroConversas conversas,
            Ferramenta... lista) {
        return new Assistente(claude, new Ferramentas(List.of(lista), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        JSON, props()),
                JSON, conversas, new dev.jarvis.conversa.AnexosService(),
                BuscaWebServidor.desligada(), new dev.jarvis.aprovacao.Aprovacoes(), arquivo);
    }

    /** Uma ferramenta que faz o que a de esquecer memória faz: marca o turno como "esqueceu". */
    private Ferramenta esquecedor() {
        return new Ferramenta() {
            public String nome() { return "esquecer_tudo"; }
            public String descricao() { return "Esquece a memória pessoal"; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) { return ""; }
            public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
                contexto.marcarMemoriaEsquecida("11111111-1111-1111-1111-111111111111", 3);
                return ResultadoFerramenta.texto("Esquecido.");
            }
        };
    }

    private static OuvinteProgresso autenticado(String id, long usuario) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return id; }
            @Override public long usuarioAutenticado() { return usuario; }
        };
    }

    private static ObjectNode mensagem(String papel, String texto) {
        return JSON.createObjectNode().put("role", papel).put("content", texto);
    }

    private static JsonNode resposta(String texto) {
        ObjectNode resposta = JSON.createObjectNode().put("stop_reason", "end_turn");
        ArrayNode conteudo = resposta.putArray("content");
        // Com pensamento de propósito: ele não pode chegar ao disco, como já não chega à memória.
        conteudo.addObject().put("type", "thinking").put("thinking", "deixa eu ver")
                .put("signature", "assinatura");
        conteudo.addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    private static JsonNode chamada(String nome) {
        var resposta = JSON.createObjectNode().put("stop_reason", "tool_use");
        resposta.putArray("content").addObject().put("type", "tool_use").put("id", "tu_1")
                .put("name", nome).set("input", JSON.createObjectNode());
        return resposta;
    }

    private ArrayNode ultimasMensagens() throws Exception {
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                captor.capture(),
                any(ArrayNode.class));
        return captor.getAllValues().getLast();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }
}
