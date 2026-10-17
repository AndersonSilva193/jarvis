package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.OuvirRespostaFerramenta;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.conversa.Confirmacao;
import dev.jarvis.conversa.EscutaResposta;
import dev.jarvis.conversa.OuvinteProgresso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que o Assistente manda para a API, com o ClaudeClient dublado: sem rede e sem Spring.
 *
 * O foco é o "preserved thinking" — os blocos de pensamento vêm assinados e presos ao prefixo exato
 * (system + ferramentas + mensagens) que os produziu. Replicar um deles sob outro prefixo é HTTP 400,
 * e foi assim que o bot quebrou em produção.
 */
class AssistenteTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final Assistente assistente = new Assistente(
            claude,
            new Ferramentas(List.of(), JSON),
            new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props()),
            JSON);

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }

    /** Resposta com um bloco de pensamento assinado antes do texto, como o modelo devolve. */
    private static JsonNode respostaComPensamento(String texto) {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "end_turn");
        ArrayNode conteudo = resposta.putArray("content");
        ObjectNode pensamento = conteudo.addObject();
        pensamento.put("type", "thinking");
        pensamento.put("thinking", "deixa eu pensar...");
        pensamento.put("signature", "assinatura-presa-ao-prefixo");
        conteudo.addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    /** Resposta que pede uma ferramenta, também precedida de pensamento. */
    private static JsonNode respostaComFerramenta() {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "tool_use");
        ArrayNode conteudo = resposta.putArray("content");
        ObjectNode pensamento = conteudo.addObject();
        pensamento.put("type", "thinking");
        pensamento.put("thinking", "preciso de uma ferramenta");
        pensamento.put("signature", "assinatura");
        ObjectNode uso = conteudo.addObject();
        uso.put("type", "tool_use");
        uso.put("id", "tu_1");
        uso.put("name", "inexistente");
        uso.set("input", JSON.createObjectNode());
        return resposta;
    }

    /**
     * Resposta de busca na web: os blocos vêm prontos do servidor da Anthropic, e o
     * encrypted_content é o que ela decifra nos turnos seguintes.
     */
    private static ArrayNode blocosDeBusca(JsonNode destino) {
        ArrayNode conteudo = (ArrayNode) destino;
        ObjectNode uso = conteudo.addObject();
        uso.put("type", "server_tool_use");
        uso.put("id", "srvtoolu_1");
        uso.put("name", "web_search");
        uso.putObject("input").put("query", "prefeito de Curitiba");
        ObjectNode resultado = conteudo.addObject();
        resultado.put("type", "web_search_tool_result");
        resultado.put("tool_use_id", "srvtoolu_1");
        ObjectNode achado = resultado.putArray("content").addObject();
        achado.put("type", "web_search_result");
        achado.put("url", "https://exemplo.com");
        achado.put("title", "Exemplo");
        achado.put("encrypted_content", "EqgfCioIARgBIiQ3");
        return conteudo;
    }

    /** Turno que o servidor pausou no meio da busca. */
    private static JsonNode respostaPausada() {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "pause_turn");
        blocosDeBusca(resposta.putArray("content"));
        return resposta;
    }

    private Assistente comFerramentas(Ferramenta... lista) {
        return new Assistente(claude, new Ferramentas(List.of(lista), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), JSON, props()), JSON);
    }

    private JsonNode chamada(String nome, ObjectNode entrada) {
        var resposta = JSON.createObjectNode().put("stop_reason", "tool_use");
        resposta.putArray("content").addObject().put("type", "tool_use").put("id", "tu_1")
                .put("name", nome).set("input", entrada);
        return resposta;
    }

    @Test void criarLocalizarLerEReceberResumoNoMesmoTurno() throws Exception {
        Ferramenta criar = ferramentaDeFluxo("criar_arquivo", "Arquivo criado: relatorio.txt.");
        Ferramenta listar = ferramentaDeFluxo("listar_arquivos",
                "{\"arquivos\":[{\"id\":\"doc-123\",\"nome\":\"relatorio.txt\"}]}");
        Ferramenta ler = ferramentaDeFluxo("ler_arquivo",
                "{\"id\":\"doc-123\",\"conteudo\":\"Receita de outubro: 42\",\"truncado\":false}");
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("criar_arquivo", JSON.createObjectNode()))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode().put("nome", "relatorio")))
                .thenReturn(chamada("ler_arquivo", JSON.createObjectNode().put("id", "doc-123")))
                .thenReturn(respostaComPensamento("O relatório registra receita de outubro de 42."));
        var resposta = comFerramentas(criar, listar, ler).responder(1L,
                "Crie o relatório, encontre o arquivo e resuma", Canal.TELEGRAM, null);
        assertEquals("O relatório registra receita de outubro de 42.", resposta.texto());
        var mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(4)).criarMensagem(any(ArrayNode.class), mensagens.capture(), any(ArrayNode.class));
        assertTrue(mensagens.getValue().toString().contains("Receita de outubro: 42"));
    }

    private Ferramenta ferramentaDeFluxo(String nome, String retorno) {
        return new Ferramenta() {
            public String nome() { return nome; }
            public String descricao() { return nome; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) { return retorno; }
        };
    }

    @Test void erroDeCriacaoRepetidoEncerraLoopComMotivo() throws Exception {
        Ferramenta arquivo = new Ferramenta() {
            public String nome() { return "criar_arquivo"; }
            public String descricao() { return "Cria arquivo"; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) { return "Nome inválido: informe somente o nome."; }
        };
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("criar_arquivo", JSON.createObjectNode()));
        var resposta = comFerramentas(arquivo).responder(1L, "crie um arquivo", Canal.HUD, null);
        assertTrue(resposta.texto().contains("Nome inválido"));
        verify(claude, times(2)).criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
    }

    @Test void escutaDaFerramentaChegaARespostaHudEUsaUltimaNaoNula() throws Exception {
        var entrada = JSON.createObjectNode().put("tempo", 12).put("motivo", "confirmar o horário");
        entrada.putArray("opcoes").add("sim").add("não");
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("ouvir_resposta", JSON.createObjectNode().put("motivo", "primeira pergunta")))
                .thenReturn(chamada("ouvir_resposta", entrada))
                .thenReturn(chamada("inexistente", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Pode ser às oito?"));
        var resposta = comFerramentas(new OuvirRespostaFerramenta(JSON)).responder(1L, "agende", Canal.HUD, null);
        assertEquals(new EscutaResposta(12, List.of("sim", "não"), "confirmar o horário"), resposta.escuta());
        assertEquals("Pode ser às oito?", resposta.texto());
        var captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(4)).criarMensagem(any(ArrayNode.class), any(ArrayNode.class), captor.capture());
        for (var definicoes : captor.getAllValues()) assertSame(captor.getValue(), definicoes);
        assertEquals("ouvir_resposta", captor.getValue().get(0).path("name").stringValue());
    }

    private Ferramenta ferramentaDeConfirmacao(Confirmacao confirmacao, EscutaResposta escuta) {
        return new Ferramenta() {
            public String nome() { return "confirmar_teste"; }
            public String descricao() { return "Teste de confirmação"; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode(); }
            public String executar(JsonNode entrada) { return "Confirme"; }
            public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
                return new ResultadoFerramenta("Confirme", List.of(), List.of(), confirmacao,
                        List.of(), List.of(), escuta);
            }
        };
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = { false, true })
    void confirmacaoHudGanhaEscutaAutomaticaMesmoSemFerramentaDeEscuta(boolean temEscutaExplicita) throws Exception {
        var confirmacao = new Confirmacao("1234", "reiniciar o PC", Instant.now().plusSeconds(120).toString());
        var explicita = new EscutaResposta(8, List.of("sim"), "outra pergunta");
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("confirmar_teste", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Qual é o código?"));
        var resposta = comFerramentas(ferramentaDeConfirmacao(confirmacao, temEscutaExplicita ? explicita : null))
                .responder(1L, "reinicie", Canal.HUD, null);
        assertEquals(confirmacao, resposta.confirmacao());
        assertEquals(new EscutaResposta(20, List.of("1234", "cancelar"), "reiniciar o PC"), resposta.escuta());
    }

    @Test void telegramNaoRecebeEscutaNemOfereceAFerramenta() throws Exception {
        var confirmacao = new Confirmacao("1234", "reiniciar", Instant.now().plusSeconds(120).toString());
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("confirmar_teste", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Confirme"));
        var resposta = comFerramentas(new OuvirRespostaFerramenta(JSON),
                ferramentaDeConfirmacao(confirmacao, new EscutaResposta(8, null, "confirmar")))
                .responder(1L, "reinicie", Canal.TELEGRAM, null);
        assertNull(resposta.escuta());
        var captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(ArrayNode.class), any(ArrayNode.class), captor.capture());
        assertFalse(captor.getValue().toString().contains("ouvir_resposta"));
    }

    @Test void tempoDaConfirmacaoRespeitaPrazoSemMinimoDeCincoSegundos() {
        var agora = Instant.parse("2026-10-16T12:00:00Z");
        assertEquals(20, Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar",
                agora.plusSeconds(120).toString()), agora).tempoSegundos());
        assertEquals(3, Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar",
                agora.plusMillis(3900).toString()), agora).tempoSegundos());
        for (String prazo : new String[] { agora.minusSeconds(1).toString(), agora.toString(), "inválido", null }) {
            var escuta = Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar", prazo), agora);
            assertEquals(0, escuta.tempoSegundos());
            assertEquals(List.of("1234", "cancelar"), escuta.opcoes());
        }
    }

    private List<ArrayNode> mensagensEnviadas() throws Exception {
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce())
                .criarMensagem(any(ArrayNode.class), captor.capture(), any(ArrayNode.class));
        return captor.getAllValues();
    }

    private static List<JsonNode> blocosDe(ArrayNode mensagens, String tipo) {
        List<JsonNode> achados = new ArrayList<>();
        for (JsonNode mensagem : mensagens) {
            JsonNode conteudo = mensagem.path("content");
            for (int i = 0; conteudo.isArray() && i < conteudo.size(); i++) {
                if (tipo.equals(conteudo.get(i).path("type").stringValue())) {
                    achados.add(conteudo.get(i));
                }
            }
        }
        return achados;
    }

    @Test
    @DisplayName("o histórico replicado não leva blocos de pensamento do turno anterior")
    void historicoSemPensamento() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComPensamento("pronto, senhor"));

        assistente.responder(1L, "oi");
        assistente.responder(1L, "e agora?");

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(2, enviadas.size());

        ArrayNode segundoPedido = enviadas.get(1);
        assertTrue(segundoPedido.size() >= 3, "o segundo pedido tem que carregar o turno anterior");
        assertTrue(blocosDe(segundoPedido, "thinking").isEmpty(),
                "pensamento do turno anterior iria assinado sob outro prefixo: HTTP 400");
        assertFalse(blocosDe(segundoPedido, "text").isEmpty(),
                "o texto da resposta anterior tem que continuar no histórico");
    }

    @Test
    @DisplayName("dentro do mesmo turno o pensamento é devolvido intacto, como o laço de ferramentas exige")
    void pensamentoPreservadoDentroDoTurno() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComFerramenta())
                .thenReturn(respostaComPensamento("feito"));

        assistente.responder(1L, "verifica o site");

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(2, enviadas.size(), "uma chamada para pedir a ferramenta e outra com o resultado");
        assertFalse(blocosDe(enviadas.get(1), "thinking").isEmpty(),
                "o pensamento que acompanha o tool_use tem que voltar no mesmo turno");
    }

    @Test
    @DisplayName("o system é montado uma vez por pedido e repetido em todas as idas e vindas")
    void systemEstavelDuranteOTurno() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaComFerramenta())
                .thenReturn(respostaComPensamento("feito"));

        assistente.responder(1L, "verifica o site");

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2))
                .criarMensagem(captor.capture(), any(ArrayNode.class), any(ArrayNode.class));

        // Identidade, não igualdade: no mesmo minuto o texto sairia igual mesmo remontado, e o
        // teste passaria com o bug. O que precisa valer é que foi montado uma vez só.
        assertSame(captor.getAllValues().get(0), captor.getAllValues().get(1),
                "remontar o system no meio do turno invalida o pensamento já devolvido");
    }

    @Test
    @DisplayName("pause_turn continua o mesmo turno, com os blocos da busca inalterados")
    void pausaContinuaOTurno() throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(respostaPausada())
                .thenReturn(respostaComPensamento("é o Eduardo Pimentel"));

        String resposta = assistente.responder(1L, "quem é o prefeito de Curitiba?");

        assertEquals("é o Eduardo Pimentel", resposta);
        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(2, enviadas.size(), "pause_turn não encerra o turno: reenviamos e seguimos");

        List<JsonNode> achados = blocosDe(enviadas.get(1), "web_search_tool_result");
        assertEquals(1, achados.size(), "o resultado da busca tem que voltar no mesmo turno");
        assertEquals("EqgfCioIARgBIiQ3",
                achados.get(0).path("content").get(0).path("encrypted_content").stringValue(),
                "encrypted_content alterado ou ausente é HTTP 400");
    }

    @Test
    @DisplayName("bloco server-side não é executado aqui nem vira mensagem de content vazio")
    void blocoServerSideNaoEExecutado() throws Exception {
        ObjectNode soBusca = JSON.createObjectNode();
        // stop_reason de ferramenta, mas no conteúdo só o que o servidor já resolveu.
        soBusca.put("stop_reason", "tool_use");
        blocosDeBusca(soBusca.putArray("content")).addObject().put("type", "text").put("text", "achei");
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(soBusca);

        assertEquals("achei", assistente.responder(1L, "procura aí"));

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(1, enviadas.size(), "não há ferramenta nossa para executar: o turno acaba aqui");
        assertTrue(blocosDe(enviadas.get(0), "tool_result").isEmpty());
    }
}
