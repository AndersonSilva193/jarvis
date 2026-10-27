package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.custo.Origem;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.aprovacao.ClassificadorDeResposta;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.OuvirRespostaFerramenta;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.conversa.Confirmacao;
import dev.jarvis.conversa.EscutaResposta;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.conversa.Anexo;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.conversa.RespostaConversa;
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

    @Test void referenciaSomenteAnexoUnicoDaRespostaAnteriorDoMesmoCanal() {
        Instant agora = Instant.parse("2026-10-18T21:00:00Z");
        Anexo arquivo = new Anexo("a", "arquivo", "nota.md", "application/octet-stream", "", "");
        Anexo outro = new Anexo("b", "arquivo", "outro.txt", "application/octet-stream", "", "");
        assertEquals("nota.md", Assistente.ultimoAnexoDeArquivo(
                historicoComAnexos("hud", agora.minusSeconds(60), List.of(arquivo)), Canal.HUD, agora));
        assertEquals("", Assistente.ultimoAnexoDeArquivo(
                historicoComAnexos("hud", agora.minusSeconds(60), List.of(arquivo, outro)), Canal.HUD, agora));
        assertEquals("", Assistente.ultimoAnexoDeArquivo(
                historicoComAnexos("hud", agora.minusSeconds(601), List.of(arquivo)), Canal.HUD, agora));
        assertEquals("", Assistente.ultimoAnexoDeArquivo(
                historicoComAnexos("telegram", agora.minusSeconds(60), List.of(arquivo)), Canal.HUD, agora));
        assertEquals("", Assistente.ultimoAnexoDeArquivo(
                historicoComAnexos("hud", agora.minusSeconds(60), List.of()), Canal.HUD, agora));
    }

    private static RegistroConversas.Historico historicoComAnexos(String canal, Instant quando,
            List<Anexo> arquivos) {
        var resposta = new RespostaConversa("resposta", canal, "", arquivos, null,
                quando.toString(), null);
        var troca = new RegistroConversas.Troca("resposta", canal, "pedido", resposta);
        return new RegistroConversas.Historico("sessao", 1, List.of(troca), null, resposta);
    }

    @Test void anexoAnteriorAutenticadoChegaAFerramentaNoTelegram() throws Exception {
        var conversas = new RegistroConversas(evento -> { });
        var anexo = new Anexo("a", "arquivo", "relatorio.md", "application/octet-stream", "", "");
        conversas.concluir(42, "pedido anterior", new RespostaConversa("anterior", "telegram",
                "Arquivo criado", List.of(anexo), null, Instant.now().toString(), null));
        Ferramenta observar = new Ferramenta() {
            public String nome() { return "observar_anexo"; }
            public String descricao() { return "Observa referência"; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) { return ""; }
            public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
                return ResultadoFerramenta.texto(contexto.ultimoAnexoDeArquivo());
            }
        };
        var assistenteComHistorico = new Assistente(claude, new Ferramentas(List.of(observar), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        JSON, props()), JSON, conversas, new dev.jarvis.conversa.AnexosService(),
                BuscaWebServidor.desligada(), new dev.jarvis.aprovacao.Aprovacoes());
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("observar_anexo", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("feito"));
        assistenteComHistorico.responder(42, "edite esse arquivo", Canal.TELEGRAM,
                new OuvinteProgresso() {
                    @Override public String idPedido() { return "telegram-2"; }
                    @Override public long usuarioAutenticado() { return 42; }
                });
        assertTrue(mensagensEnviadas().getLast().toString().contains("relatorio.md"));
    }

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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("criar_arquivo", JSON.createObjectNode()))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode().put("nome", "relatorio")))
                .thenReturn(chamada("ler_arquivo", JSON.createObjectNode().put("id", "doc-123")))
                .thenReturn(respostaComPensamento("O relatório registra receita de outubro de 42."));
        var resposta = comFerramentas(criar, listar, ler).responder(1L,
                "Crie o relatório, encontre o arquivo e resuma", Canal.TELEGRAM, null);
        assertEquals("O relatório registra receita de outubro de 42.", resposta.texto());
        var mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(4)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                mensagens.capture(), any(ArrayNode.class));
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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("criar_arquivo", JSON.createObjectNode()));
        var resposta = comFerramentas(arquivo).responder(1L, "crie um arquivo", Canal.HUD, null);
        assertTrue(resposta.texto().contains("Nome inválido"));
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
    }

    /** Irmã da ferramentaDeFluxo: aqui o que importa é provar que a ferramenta <b>não</b> rodou. */
    private Ferramenta ferramentaQueConta(String nome, String retorno, AtomicInteger execucoes) {
        return new Ferramenta() {
            public String nome() { return nome; }
            public String descricao() { return nome; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) { execucoes.incrementAndGet(); return retorno; }
        };
    }

    @Test
    @DisplayName("a terceira chamada idêntica é recusada sem executar, mesmo com as chaves trocadas de ordem")
    void terceiraChamadaIdenticaNaoExecuta() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Ferramenta listar = ferramentaQueConta("listar_arquivos", "{\"arquivos\":[]}", execucoes);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("listar_arquivos",
                        JSON.createObjectNode().put("nome", "nota").put("pasta", "jarvis")))
                // A mesma entrada com as chaves ao contrário: é o JSON canônico que decide.
                .thenReturn(chamada("listar_arquivos",
                        JSON.createObjectNode().put("pasta", "jarvis").put("nome", "nota")))
                .thenReturn(chamada("listar_arquivos",
                        JSON.createObjectNode().put("nome", "nota").put("pasta", "jarvis")))
                .thenReturn(respostaComPensamento("Não achei nada com esse nome."));

        var resposta = comFerramentas(listar).responder(1L, "procure a nota", Canal.HUD, null);

        assertEquals(2, execucoes.get(), "a terceira não chega a executar");
        assertEquals("Não achei nada com esse nome.", resposta.texto());
        assertTrue(mensagensEnviadas().getLast().toString().contains(
                "Você já chamou listar_arquivos com esta mesma entrada duas vezes neste pedido"));
    }

    @Test
    @DisplayName("consulta de estado aguenta mais: a recusa só vem na quinta chamada igual")
    void consultaDeEstadoSoParaNaQuinta() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Ferramenta status = ferramentaQueConta("status_operacao_pc", "ainda rodando", execucoes);
        var entrada = JSON.createObjectNode().put("id", "op-1");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("status_operacao_pc", entrada))
                .thenReturn(chamada("status_operacao_pc", entrada))
                .thenReturn(chamada("status_operacao_pc", entrada))
                .thenReturn(chamada("status_operacao_pc", entrada))
                .thenReturn(chamada("status_operacao_pc", entrada))
                .thenReturn(respostaComPensamento("A operação ainda está rodando."));

        var resposta = comFerramentas(status).responder(1L, "já terminou?", Canal.HUD, null);

        assertEquals(4, execucoes.get(), "quatro passam; a quinta é recusada");
        assertEquals("A operação ainda está rodando.", resposta.texto());
        assertTrue(mensagensEnviadas().getLast().toString().contains(
                "Você já chamou status_operacao_pc com esta mesma entrada quatro vezes neste pedido"));
    }

    @Test
    @DisplayName("controlar_tela entra no limite folgado pela lista, não pelo prefixo")
    void acaoEmPassosTambemTemLimiteFolgado() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Ferramenta tela = ferramentaQueConta("controlar_tela", "Volume abaixado.", execucoes);
        var entrada = JSON.createObjectNode().put("acao", "volume_menos");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("controlar_tela", entrada))
                .thenReturn(chamada("controlar_tela", entrada))
                .thenReturn(chamada("controlar_tela", entrada))
                .thenReturn(chamada("controlar_tela", entrada))
                .thenReturn(respostaComPensamento("Pronto, bem mais baixo."));

        comFerramentas(tela).responder(1L, "abaixa bastante o volume", Canal.HUD, null);

        assertEquals(4, execucoes.get(), "quatro passos de volume não são um laço");
    }

    @Test
    @DisplayName("insistir na repetição encerra o turno com o último resultado útil")
    void doisBloqueiosEncerramOTurno() throws Exception {
        AtomicInteger execucoes = new AtomicInteger();
        Ferramenta clima = ferramentaQueConta("clima", "Sol, 28 graus.", execucoes);
        // Um thenReturn só se repete para sempre: é o modelo que não desiste.
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("clima", JSON.createObjectNode().put("cidade", "Recife")));

        var resposta = comFerramentas(clima).responder(1L, "e o tempo?", Canal.HUD, null);

        assertEquals("Parei porque estava repetindo a mesma consulta sem avançar. Sol, 28 graus.",
                resposta.texto());
        assertEquals(2, execucoes.get());
        verify(claude, times(4)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class),
                any(ArrayNode.class));
    }

    @Test void edicaoRecusadaNaoViraCriacaoDeOutroArquivo() throws Exception {
        Ferramenta editar = ferramentaDeFluxo("editar_arquivo",
                "O arquivo proposto não foi identificado no pedido original.");
        Ferramenta criar = mock(Ferramenta.class);
        when(criar.nome()).thenReturn("criar_arquivo");
        when(criar.descricao()).thenReturn("Criar arquivo");
        when(criar.esquemaEntrada()).thenReturn(JSON.createObjectNode().put("type", "object"));
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("editar_arquivo", JSON.createObjectNode()))
                .thenReturn(chamada("criar_arquivo", JSON.createObjectNode()));

        var resposta = comFerramentas(editar, criar).responder(1L,
                "edite esse arquivo", Canal.HUD, null);

        assertTrue(resposta.texto().contains("não criei um substituto"));
        verify(criar, org.mockito.Mockito.never()).executarDetalhado(any(), any());
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
    }

    @Test void escutaDaFerramentaChegaARespostaHudEUsaUltimaNaoNula() throws Exception {
        var entrada = JSON.createObjectNode().put("tempo", 12).put("motivo", "confirmar o horário");
        entrada.putArray("opcoes").add("sim").add("não");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("ouvir_resposta", JSON.createObjectNode().put("motivo", "primeira pergunta")))
                .thenReturn(chamada("ouvir_resposta", entrada))
                .thenReturn(chamada("inexistente", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Pode ser às oito?"));
        var resposta = comFerramentas(new OuvirRespostaFerramenta(JSON)).responder(1L, "agende", Canal.HUD, null);
        assertEquals(new EscutaResposta(12, List.of("sim", "não"), "confirmar o horário"), resposta.escuta());
        assertEquals("Pode ser às oito?", resposta.texto());
        var captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(4)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), captor.capture());
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
        var confirmacao = new Confirmacao("1234", "reiniciar o PC",
                Instant.now().plusSeconds(120).toString(), "Vou reiniciar o PC em 60 segundos. Posso?");
        var explicita = new EscutaResposta(8, List.of("sim"), "outra pergunta");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("confirmar_teste", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Qual é o código?"));
        var resposta = comFerramentas(ferramentaDeConfirmacao(confirmacao, temEscutaExplicita ? explicita : null))
                .responder(1L, "reinicie", Canal.HUD, null);
        assertEquals(confirmacao, resposta.confirmacao());
        assertEquals(new EscutaResposta(20, List.of(), "Vou reiniciar o PC em 60 segundos. Posso?"),
                resposta.escuta(), "sem opções: qualquer fala do usuário responde a pergunta");
    }

    @Test void telegramNaoRecebeEscutaNemOfereceAFerramenta() throws Exception {
        var confirmacao = new Confirmacao("1234", "reiniciar", Instant.now().plusSeconds(120).toString());
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("confirmar_teste", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Confirme"));
        var resposta = comFerramentas(new OuvirRespostaFerramenta(JSON),
                ferramentaDeConfirmacao(confirmacao, new EscutaResposta(8, null, "confirmar")))
                .responder(1L, "reinicie", Canal.TELEGRAM, null);
        assertNull(resposta.escuta());
        var captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), captor.capture());
        assertFalse(captor.getValue().toString().contains("ouvir_resposta"));
    }

    @Test void tempoDaConfirmacaoRespeitaPrazoSemMinimoDeCincoSegundos() {
        var agora = Instant.parse("2026-10-16T12:00:00Z");
        assertEquals(20, Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar",
                agora.plusSeconds(120).toString()), agora).tempoSegundos());
        assertEquals(3, Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar",
                agora.plusMillis(3900).toString()), agora).tempoSegundos());
        for (String prazo : new String[] { agora.minusSeconds(1).toString(), agora.toString(), "inválido", null }) {
            var escuta = Assistente.escutaDaConfirmacao(new Confirmacao("1234", "confirmar", prazo,
                    "Posso trocar o arquivo?"), agora);
            assertEquals(0, escuta.tempoSegundos());
            assertEquals(List.of(), escuta.opcoes());
            assertEquals("Posso trocar o arquivo?", escuta.motivo(),
                    "a HUD mostra a pergunta que o usuário está respondendo");
        }
    }

    private List<ArrayNode> mensagensEnviadas() throws Exception {
        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, atLeastOnce())
                .criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                        any(ArrayNode.class), captor.capture(),
                        any(ArrayNode.class));
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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(respostaComFerramenta())
                .thenReturn(respostaComPensamento("feito"));

        assistente.responder(1L, "verifica o site");

        ArgumentCaptor<ArrayNode> captor = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2))
                .criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                        captor.capture(), any(ArrayNode.class),
                        any(ArrayNode.class));

        // Identidade, não igualdade: no mesmo minuto o texto sairia igual mesmo remontado, e o
        // teste passaria com o bug. O que precisa valer é que foi montado uma vez só.
        assertSame(captor.getAllValues().get(0), captor.getAllValues().get(1),
                "remontar o system no meio do turno invalida o pensamento já devolvido");
    }

    @Test
    @DisplayName("ler memória não bloqueia a ferramenta seguinte no mesmo pedido")
    void buscarMemoriaNaoBloqueiaAFerramentaSeguinte() throws Exception {
        // A trava antiga era por nome de ferramenta, então o dublê basta para provar que ela saiu.
        Ferramenta buscar = ferramentaDeFluxo("buscar_memoria",
                "{\"achados\":[{\"assunto\":\"cidade\",\"trecho\":\"Curitiba\"}]}");
        Ferramenta lembrete = ferramentaDeFluxo("criar_lembrete", "Lembrete criado para amanhã.");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("buscar_memoria", JSON.createObjectNode().put("consulta", "cidade")))
                .thenReturn(chamada("criar_lembrete", JSON.createObjectNode().put("texto", "feira")))
                .thenReturn(respostaComPensamento("Lembrete criado, senhor."));

        var resposta = comFerramentas(buscar, lembrete).responder(1L, "qual minha cidade? cria um lembrete",
                Canal.TELEGRAM, null);

        assertEquals("Lembrete criado, senhor.", resposta.texto());
        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(3)).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                mensagens.capture(), any(ArrayNode.class));
        assertTrue(mensagens.getValue().toString().contains("Lembrete criado para amanhã."),
                "a segunda ferramenta rodou de verdade: " + mensagens.getValue());
        assertFalse(mensagens.getValue().toString().contains("não autoriza outras ferramentas"));
    }

    @Test
    @DisplayName("pause_turn continua o mesmo turno, com os blocos da busca inalterados")
    void pausaContinuaOTurno() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
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
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(soBusca);

        assertEquals("achei", assistente.responder(1L, "procura aí"));

        List<ArrayNode> enviadas = mensagensEnviadas();
        assertEquals(1, enviadas.size(), "não há ferramenta nossa para executar: o turno acaba aqui");
        assertTrue(blocosDe(enviadas.get(0), "tool_result").isEmpty());
    }

    // --- A resposta em linguagem natural a uma aprovação pendente ---------------------------

    private final dev.jarvis.pc.RelogioFalso relogio = new dev.jarvis.pc.RelogioFalso();
    private final Aprovacoes aprovacoes = new Aprovacoes(relogio);
    private final ClassificadorDeResposta classificador = mock(ClassificadorDeResposta.class);
    private final java.util.concurrent.atomic.AtomicInteger desligamentos =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Monta o assistente com uma aprovação já pendente, criada pelo pedido "telegram-1". */
    private Assistente comAprovacaoPendente() {
        aprovacoes.pedir("pc", "desligar o PC", "Vou desligar o PC em 60 segundos. Posso?", Aprovacoes.Risco.CRITICO,
                42, "telegram-1", () -> "Desligando em 60 segundos, senhor. " + desligamentos.incrementAndGet());
        return new Assistente(claude, new Ferramentas(List.of(), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        JSON, props()), JSON, new RegistroConversas(evento -> { }),
                new dev.jarvis.conversa.AnexosService(), BuscaWebServidor.desligada(), aprovacoes,
                dev.jarvis.conversa.HistoricoEmArquivo.desligado(), classificador);
    }

    private String responderComoDono(Assistente agente, String texto) throws Exception {
        return agente.responder(42, texto, Canal.TELEGRAM, new OuvinteProgresso() {
            @Override public String idPedido() { return "telegram-2"; }
            @Override public long usuarioAutenticado() { return 42; }
        }).texto();
    }

    @Test
    @DisplayName("SIM executa a pendência sem passar pelo modelo")
    void simExecutaAPendencia() throws Exception {
        when(classificador.classificar(any(), any())).thenReturn(ClassificadorDeResposta.Decisao.SIM);
        Assistente agente = comAprovacaoPendente();

        assertEquals("Desligando em 60 segundos, senhor. 1", responderComoDono(agente, "manda ver"));
        assertEquals(1, desligamentos.get());
        verify(claude, never()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
        assertTrue(aprovacoes.pendenciaDe(42, "telegram-3").isEmpty(), "uso único");
    }

    @Test
    @DisplayName("NÃO cancela e responde numa linha")
    void naoCancelaAPendencia() throws Exception {
        when(classificador.classificar(any(), any())).thenReturn(ClassificadorDeResposta.Decisao.NAO);
        Assistente agente = comAprovacaoPendente();

        assertEquals("Certo, não fiz.", responderComoDono(agente, "agora não"));
        assertEquals(0, desligamentos.get());
        verify(claude, never()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
        assertTrue(aprovacoes.pendenciaDe(42, "telegram-3").isEmpty());
    }

    @Test
    @DisplayName("AJUSTE cancela a proposta e segue para o modelo com a nota no turno")
    void ajusteCancelaESegueParaOModelo() throws Exception {
        when(classificador.classificar(any(), any())).thenReturn(ClassificadorDeResposta.Decisao.AJUSTE);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(respostaComPensamento("Então só reinicio, senhor."));
        Assistente agente = comAprovacaoPendente();

        assertEquals("Então só reinicio, senhor.",
                responderComoDono(agente, "pode, mas reinicia em vez de desligar"));
        assertEquals(0, desligamentos.get());
        String enviado = mensagensEnviadas().getFirst().toString();
        assertTrue(enviado.contains("pode, mas reinicia em vez de desligar"), enviado);
        assertTrue(enviado.contains("nota do servidor"), "o modelo precisa saber que a proposta caiu: " + enviado);
        assertTrue(enviado.contains("Vou desligar o PC em 60 segundos. Posso?"), enviado);
        assertTrue(aprovacoes.pendenciaDe(42, "telegram-3").isEmpty(), "a proposta de antes não vale mais");
    }

    @Test
    @DisplayName("OUTRO ASSUNTO mantém a pendência e toca a conversa")
    void outroAssuntoMantemAPendencia() throws Exception {
        when(classificador.classificar(any(), any()))
                .thenReturn(ClassificadorDeResposta.Decisao.OUTRO_ASSUNTO);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(respostaComPensamento("Faz 23 graus, senhor."));
        Assistente agente = comAprovacaoPendente();

        assertEquals("Faz 23 graus, senhor.", responderComoDono(agente, "qual a temperatura agora?"));
        assertEquals(0, desligamentos.get());
        assertFalse(mensagensEnviadas().getFirst().toString().contains("nota do servidor"));
        assertEquals("Vou desligar o PC em 60 segundos. Posso?", aprovacoes.pendenciaDe(42, "telegram-3").orElseThrow(),
                "quem não respondeu a pergunta não cancelou nada");
    }

    @Test
    @DisplayName("DÚVIDA não executa: repete a pergunta e mantém a pendência")
    void duvidaRepeteAPergunta() throws Exception {
        when(classificador.classificar(any(), any())).thenReturn(ClassificadorDeResposta.Decisao.DUVIDA);
        Assistente agente = comAprovacaoPendente();

        assertEquals("Só para confirmar, senhor: Vou desligar o PC em 60 segundos. Posso?",
                responderComoDono(agente, "sei lá, você que sabe"));
        assertEquals(0, desligamentos.get());
        verify(claude, never()).criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class),
                any(ArrayNode.class), any(ArrayNode.class));
        assertEquals("Vou desligar o PC em 60 segundos. Posso?", aprovacoes.pendenciaDe(42, "telegram-3").orElseThrow());
    }

    // ---- O histórico compacto: turno antigo não reenvia resultado de ferramenta nem busca ----

    /** Um UUID de verdade: é o que o catálogo devolve, e é inteiro que ele tem de voltar na nota. */
    private static final String ID_NOTAS = "3f9c1a20-4b7e-4c11-9d02-7a1e55c8b3df";
    private static final String ID_IDEIAS = "a7d2e418-91bc-4f03-8e55-2c6b0d94af17";
    private static final String LISTAGEM = "{\"arquivos\":["
            + "{\"id\":\"" + ID_NOTAS + "\",\"nome\":\"notas.md\"},"
            + "{\"id\":\"" + ID_IDEIAS + "\",\"nome\":\"ideias.md\"}]}";

    private Assistente assistenteCom(int turnosCompletos, Ferramenta... lista) {
        return new Assistente(claude, new Ferramentas(List.of(lista), JSON),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        JSON, props()),
                JSON, new RegistroConversas(evento -> { }), new dev.jarvis.conversa.AnexosService(),
                BuscaWebServidor.desligada(), new Aprovacoes(),
                dev.jarvis.conversa.HistoricoEmArquivo.desligado(),
                ClassificadorDeResposta.semModelo(), null,
                new dev.jarvis.memoria.EsquecimentosDeMemoria(),
                new CompactadorDeHistorico(JSON, turnosCompletos, 1500));
    }

    /** Resposta que pede uma ferramenta e, de quebra, já traz os blocos de uma busca na web. */
    private static JsonNode chamadaComBusca(String nome, ObjectNode entrada) {
        var resposta = JSON.createObjectNode().put("stop_reason", "tool_use");
        ArrayNode conteudo = resposta.putArray("content");
        blocosDeBusca(conteudo);
        conteudo.addObject().put("type", "tool_use").put("id", "tu_1").put("name", nome)
                .set("input", entrada);
        return resposta;
    }

    @Test
    @DisplayName("turno antigo com busca e ferramenta é guardado compacto: nenhum dos quatro blocos volta")
    void turnoComBuscaEFerramentasEhGuardadoCompacto() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamadaComBusca("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(0, ferramentaDeFluxo("listar_arquivos", LISTAGEM));
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "obrigado");

        ArrayNode segundoPedido = mensagensEnviadas().getLast();
        for (String tipo : List.of("tool_use", "tool_result", "server_tool_use", "web_search_tool_result")) {
            assertTrue(blocosDe(segundoPedido, tipo).isEmpty(),
                    "o turno antigo não pode reenviar blocos de " + tipo);
        }
        assertEquals(3, segundoPedido.size(),
                "o turno antigo cabe em duas mensagens, mais o pedido novo");
        assertFalse(segundoPedido.toString().contains("EqgfCioIARgBIiQ3"),
                "o encrypted_content sai junto com o par inteiro da busca");
    }

    @Test
    @DisplayName("a nota compacta traz a ferramenta, os identificadores inteiros e a consulta da busca")
    void notaCompactaTrazOsIdentificadores() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamadaComBusca("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(0, ferramentaDeFluxo("listar_arquivos", LISTAGEM));
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "obrigado");

        String segundoPedido = mensagensEnviadas().getLast().toString();
        assertTrue(segundoPedido.contains("[ferramentas: listar_arquivos → notas.md (id " + ID_NOTAS + ")"),
                "o nome da ferramenta e o identificador saem de dado real, montados em Java");
        assertTrue(segundoPedido.contains("ideias.md (id " + ID_IDEIAS + ")"));
        assertTrue(segundoPedido.contains("busca na web: 'prefeito de Curitiba'"),
                "a consulta vem do input.query do server_tool_use");
        assertTrue(segundoPedido.contains("Você tem notas.md e ideias.md."),
                "a resposta final continua no histórico");
    }

    @Test
    @DisplayName("com HISTORICO_TURNOS_COMPLETOS=1 o turno imediatamente anterior fica inteiro")
    void turnoAnteriorFicaCompleto() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(respostaComPensamento("Certo, senhor."))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(1, ferramentaDeFluxo("listar_arquivos", LISTAGEM));
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "obrigado");
        List<ArrayNode> ate2 = mensagensEnviadas();
        assertFalse(blocosDe(ate2.getLast(), "tool_result").isEmpty(),
                "logo depois da listagem o turno anterior ainda tem o resultado inteiro");

        agente.responder(1L, "e agora?");
        ArrayNode terceiroPedido = mensagensEnviadas().getLast();
        assertTrue(blocosDe(terceiroPedido, "tool_result").isEmpty(),
                "um turno depois, a listagem já saiu");
        assertTrue(terceiroPedido.toString().contains("notas.md (id " + ID_NOTAS + ")"),
                "o que sobra dela é a nota, com o identificador inteiro");
    }

    @Test
    @DisplayName("o que é enviado continua válido para a Messages API depois da compactação")
    void formatoAceitoPelaApi() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamadaComBusca("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode().put("nome", "ideias")))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(0, ferramentaDeFluxo("listar_arquivos", LISTAGEM));
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "e o ideias?");

        for (ArrayNode enviadas : mensagensEnviadas()) {
            conferirFormatoDaApi(enviadas);
        }
    }

    /**
     * O contrato que a Messages API cobra, conferido sem rede: papéis alternando, nada vazio e
     * nenhum resultado órfão. É o que prova que tirar os dois blocos da busca <b>juntos</b> — e o
     * par tool_use/tool_result junto — deixa a conversa válida.
     */
    private static void conferirFormatoDaApi(ArrayNode mensagens) {
        String anterior = "";
        for (int i = 0; i < mensagens.size(); i++) {
            JsonNode mensagem = mensagens.get(i);
            String papel = mensagem.path("role").stringValue();
            assertTrue("user".equals(papel) || "assistant".equals(papel), "papel válido: " + papel);
            assertFalse(papel.equals(anterior), "os papéis têm de alternar, e repetiram em " + i);
            anterior = papel;
            JsonNode conteudo = mensagem.path("content");
            if (conteudo.isString()) {
                assertFalse(conteudo.stringValue().isBlank(), "content vazio a API recusa");
            } else {
                assertFalse(conteudo.isEmpty(), "content vazio a API recusa");
            }
            // Todo tool_result referencia um tool_use da mensagem imediatamente anterior.
            for (JsonNode bloco : conteudo.isArray() ? conteudo : JSON.createArrayNode()) {
                String tipo = bloco.path("type").stringValue();
                if (!"tool_result".equals(tipo) && !"web_search_tool_result".equals(tipo)) continue;
                String id = bloco.path("tool_use_id").stringValue();
                JsonNode origem = "tool_result".equals(tipo) ? mensagens.get(i - 1) : mensagem;
                assertTrue(temChamada(origem, id), "tool_result órfão: " + id);
            }
            // E todo tool_use tem o resultado dele na mensagem seguinte.
            for (JsonNode bloco : conteudo.isArray() ? conteudo : JSON.createArrayNode()) {
                if (!"tool_use".equals(bloco.path("type").stringValue())) continue;
                String id = bloco.path("id").stringValue();
                assertTrue(i + 1 < mensagens.size() && temResultado(mensagens.get(i + 1), id),
                        "tool_use sem resultado: " + id);
            }
        }
    }

    private static boolean temChamada(JsonNode mensagem, String id) {
        for (JsonNode bloco : mensagem.path("content")) {
            String tipo = bloco.path("type").stringValue();
            if (("tool_use".equals(tipo) || "server_tool_use".equals(tipo))
                    && id.equals(bloco.path("id").stringValue())) return true;
        }
        return false;
    }

    private static boolean temResultado(JsonNode mensagem, String id) {
        for (JsonNode bloco : mensagem.path("content")) {
            if ("tool_result".equals(bloco.path("type").stringValue())
                    && id.equals(bloco.path("tool_use_id").stringValue())) return true;
        }
        return false;
    }

    @Test
    @DisplayName("compactar o que já está compacto devolve a mesma coisa: a nota sobrevive")
    void compactacaoEhIdempotente() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(respostaComPensamento("Certo, senhor."))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(0, ferramentaDeFluxo("listar_arquivos", LISTAGEM));
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "obrigado");
        agente.responder(1L, "e agora?");

        String terceiroPedido = mensagensEnviadas().getLast().toString();
        assertTrue(terceiroPedido.contains("notas.md (id " + ID_NOTAS + ")"),
                "a nota do primeiro turno continua inteira depois de duas passadas");
        assertTrue(terceiroPedido.contains("Você tem notas.md e ideias.md."),
                "e a resposta não encolheu de novo");
    }

    @Test
    @DisplayName("logo depois de listar, 'apaga o segundo' ainda alcança o identificador")
    void apagaOSegundoLogoDepoisDeListar() throws Exception {
        List<String> apagados = new ArrayList<>();
        Ferramenta apagar = new Ferramenta() {
            public String nome() { return "apagar_arquivo"; }
            public String descricao() { return "Apaga arquivo"; }
            public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            public String executar(JsonNode entrada) {
                apagados.add(entrada.path("id").stringValue());
                return "Apaguei ideias.md. Fica na lixeira por 30 dias; se quiser, eu volto.";
            }
        };
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(chamada("listar_arquivos", JSON.createObjectNode()))
                .thenReturn(respostaComPensamento("Você tem notas.md e ideias.md."))
                .thenReturn(chamada("apagar_arquivo", JSON.createObjectNode().put("id", ID_IDEIAS)))
                .thenReturn(respostaComPensamento("Apaguei ideias.md, senhor."));

        Assistente agente = assistenteCom(1, ferramentaDeFluxo("listar_arquivos", LISTAGEM), apagar);
        agente.responder(1L, "lista meus arquivos");
        agente.responder(1L, "apaga o segundo");

        assertTrue(mensagensEnviadas().get(2).toString().contains(ID_IDEIAS),
                "a listagem inteira ainda está lá quando o modelo escolhe o alvo");
        assertEquals(List.of(ID_IDEIAS), apagados);
    }

    @Test
    @DisplayName("resposta longa é cortada no histórico, com reticências")
    void respostaLongaEhCortadaNoHistorico() throws Exception {
        String longa = "a".repeat(4_000);
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class),
                any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(respostaComPensamento(longa))
                .thenReturn(respostaComPensamento("Pronto, senhor."));

        Assistente agente = assistenteCom(0);
        agente.responder(1L, "me conta tudo");
        agente.responder(1L, "obrigado");

        JsonNode resposta = mensagensEnviadas().getLast().get(1);
        String guardada = resposta.path("content").stringValue();
        assertEquals("assistant", resposta.path("role").stringValue());
        assertEquals(1500, guardada.length(), "o teto é HISTORICO_MAX_CARACTERES_RESPOSTA");
        assertTrue(guardada.endsWith("[…]"));
    }
}
