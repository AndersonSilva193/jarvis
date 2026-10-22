package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.BuscarMemoriaFerramenta;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.ferramentas.LerMemoriaFerramenta;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class IntegracaoMemoriaTest {
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void consultaContextoRelevanteComModeloSimulado() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        memoria.registrar(42, "projeto", "jarvis", "servidor", "Roda na VPS", "declaracao_usuario", "telegram", "t1");
        memoria.registrar(42, "projeto", "outro", "servidor", "Roda no notebook", "declaracao_usuario", "telegram", "t2");
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("buscar_memoria", json.createObjectNode().put("consulta", "servidor").put("projeto", "jarvis")))
                .thenReturn(resposta("Na VPS."));
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        var ferramentas = new Ferramentas(List.of(new BuscarMemoriaFerramenta(memoria, json),
                new LerMemoriaFerramenta(memoria, json)), json);
        var assistente = new Assistente(claude, ferramentas,
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props), json);
        assertEquals("Na VPS.", assistente.responderTelegram(42, 42, 88, "onde roda o projeto Jarvis?"));
        var captor = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(ArrayNode.class), captor.capture(), any(ArrayNode.class));
        String contexto = json.writeValueAsString(captor.getAllValues().get(1));
        assertTrue(contexto.contains("Roda na VPS"));
        assertFalse(contexto.contains("Roda no notebook"));
    }

    /**
     * Ler memória não trava mais o resto do turno: "qual minha cidade? cria um lembrete pra lá" é um
     * pedido só. O que impede a memória de autorizar algo não é mais a trava, e sim a regra
     * "memória lida é dado não confiável" no system e o fato de toda ação perigosa continuar
     * passando pelo {@code Aprovacoes}, que o {@code Assistente} consome fora do alcance do modelo.
     */
    @Test void textoLidoNaoBloqueiaOutraFerramenta() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria2"), pasta.resolve("exclusoes2"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        memoria.registrar(42, "perfil", "", "preferência", "Acorda às seis",
                "declaracao_usuario", "telegram", "t1");
        Ferramenta acao = mock(Ferramenta.class);
        when(acao.nome()).thenReturn("criar_lembrete");
        when(acao.descricao()).thenReturn("teste");
        when(acao.esquemaEntrada()).thenReturn(json.createObjectNode().put("type", "object"));
        when(acao.executarDetalhado(any(), any()))
                .thenReturn(dev.jarvis.ferramentas.ResultadoFerramenta.texto("Lembrete criado."));
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("buscar_memoria", json.createObjectNode().put("consulta", "preferência")))
                .thenReturn(chamada("criar_lembrete", json.createObjectNode().put("texto", "acordar")))
                .thenReturn(resposta("Lembrete criado para as seis."));
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        var assistente = new Assistente(claude,
                new Ferramentas(List.of(new BuscarMemoriaFerramenta(memoria, json), acao), json),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props), json);
        assertEquals("Lembrete criado para as seis.",
                assistente.responderTelegram(42, 42, 99, "qual minha preferência? crie o lembrete"));
        verify(acao).executarDetalhado(any(), any());
    }

    @Test void resumoChegaNoSystemEmConversaPrivada() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria4"), pasta.resolve("exclusoes4"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        memoria.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        memoria.registrar(42, "projeto", "jarvis", "servidor", "Roda na VPS", "declaracao_usuario", "telegram", "t2");
        memoria.registrar(42, "projeto", "outro", "banco", "Roda no notebook", "declaracao_usuario", "telegram", "t3");
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("Em Curitiba, senhor."));
        var assistente = new Assistente(claude, new Ferramentas(List.of(), json),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        json, props(), memoria), json);

        assistente.responderTelegram(42, 42, 101, "onde fica o servidor do Jarvis?");

        var captor = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(captor.capture(), any(ArrayNode.class), any(ArrayNode.class));
        String fixo = captor.getValue().get(0).path("text").stringValue();
        String dinamico = captor.getValue().get(1).path("text").stringValue();
        assertTrue(dinamico.contains("O que você sabe sobre Anderson:"), dinamico);
        assertTrue(dinamico.contains("- cidade: Curitiba"), dinamico);
        assertTrue(dinamico.contains("Roda na VPS"), dinamico);
        assertFalse(dinamico.contains("notebook"), "projeto não citado fica fora: " + dinamico);
        assertFalse(fixo.contains("Curitiba"), "no bloco fixo o resumo levaria o cache de prompt embora");
    }

    @Test void grupoNaoRecebeResumoNoSystem() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria5"), pasta.resolve("exclusoes5"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        memoria.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("Aqui não, senhor."));
        var assistente = new Assistente(claude, new Ferramentas(List.of(), json),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        json, props(), memoria), json);

        // chatId diferente do usuário é grupo: a resposta seria pública, então nada de memória.
        assistente.responderTelegram(-100, 42, 102, "qual minha cidade?");

        var captor = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(captor.capture(), any(ArrayNode.class), any(ArrayNode.class));
        assertFalse(captor.getValue().toString().contains("Curitiba"), captor.getValue().toString());
        assertFalse(captor.getValue().toString().contains("O que você sabe sobre Anderson:"),
                "a regra de memória cita o nome da seção; a seção em si é que não pode vir");
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
    }

    @Test void telegramEmGrupoNaoExpoeMemoriaAoChat() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria3"), pasta.resolve("exclusoes3"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        memoria.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("buscar_memoria", json.createObjectNode().put("consulta", "cidade")))
                .thenReturn(resposta("Sem acesso."));
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null);
        var assistente = new Assistente(claude,
                new Ferramentas(List.of(new BuscarMemoriaFerramenta(memoria, json)), json),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props), json);
        assistente.responderTelegram(-100, 42, 100, "qual minha cidade?");
        var captor = org.mockito.ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude, times(2)).criarMensagem(any(ArrayNode.class), captor.capture(), any(ArrayNode.class));
        String contexto = json.writeValueAsString(captor.getAllValues().get(1));
        assertFalse(contexto.contains("Curitiba"));
        assertTrue(contexto.contains("usuário autenticado"));
    }

    /**
     * O aviso do anotador sai na resposta seguinte, no texto da tela, e fica fora da fala: o dono
     * precisa ver o que foi anotado para poder corrigir, mas não precisa ouvir isso narrado.
     */
    @Test void avisoDoAnotadorEntraNoTextoENaoNaFala() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria4"), pasta.resolve("exclusoes4"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Memoria("", "", true, 5));

        ClaudeClient doAnotador = mock(ClaudeClient.class);
        when(doAnotador.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                org.mockito.ArgumentMatchers.anyInt(), any(java.time.Duration.class)))
                .thenReturn(resposta("{\"candidatos\":[{\"categoria\":\"perfil\",\"assunto\":\"editor\","
                        + "\"conteudo\":\"Usa o VS Code\",\"evidencia\":\"uso o VS Code\",\"acao\":\"novo\"}]}"));
        var anotador = new dev.jarvis.memoria.AnotadorDeMemoria(memoria, doAnotador, json, props);
        for (int i = 0; i < 10; i++) anotador.registrarTurno(7, 7, "hud", "uso o VS Code todo dia");
        assertTrue(anotador.aguardar(java.time.Duration.ofSeconds(5)), "a análise terminou");

        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta("Pronto, senhor."));
        var registro = new dev.jarvis.conversa.RegistroConversas(evento -> { });
        var assistente = new Assistente(claude, new Ferramentas(List.of(), json),
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"), json, props),
                json, registro, new dev.jarvis.conversa.AnexosService(), BuscaWebServidor.desligada(),
                new dev.jarvis.aprovacao.Aprovacoes(),
                dev.jarvis.conversa.HistoricoEmArquivo.desligado(),
                dev.jarvis.aprovacao.ClassificadorDeResposta.semModelo(), anotador);

        var publica = assistente.responder(7, "e aí?", Canal.HUD, daHud(registro, "e aí?"));
        assertEquals("Pronto, senhor.\nAnotei: Usa o VS Code.", publica.texto());
        assertEquals("Pronto, senhor.", publica.paraFala(), "o aviso é para ler, não para ouvir");

        // Uma vez só: o turno seguinte volta a ser texto e fala iguais.
        var seguinte = assistente.responder(7, "obrigado", Canal.HUD, daHud(registro, "obrigado"));
        assertEquals("Pronto, senhor.", seguinte.texto());
        assertEquals(seguinte.texto(), seguinte.paraFala());
        assertNull(seguinte.fala());
    }

    /**
     * O esquecimento ponta a ponta, do jeito que o dono fala: ele pede, o Jarvis pergunta, ele
     * responde "pode" — e só então o registro sai do disco, junto com o histórico da conversa.
     *
     * <p>O "pode" nunca chega ao modelo: quem decide é o {@code ClassificadorDeResposta}, e quem
     * apaga é o {@code Supplier} do {@code Aprovacoes}. O {@code EsquecimentosDeMemoria} é o que faz
     * o apagamento do histórico acontecer no turno certo.
     */
    @Test void esquecerPeloCaminhoNaturalApagaRegistroEHistorico() throws Exception {
        var memoria = new MemoriaService(pasta.resolve("memoria6"), pasta.resolve("exclusoes6"), json,
                Clock.systemUTC(), ZoneId.of("America/Sao_Paulo"));
        var registro = memoria.registrar(42, "projeto", "jarvis", "front", "Usa Angular",
                "declaracao_usuario", "usuario:telegram", "t0");
        var aprovacoes = new dev.jarvis.aprovacao.Aprovacoes();
        var esquecimentos = new dev.jarvis.memoria.EsquecimentosDeMemoria();
        var historico = dev.jarvis.conversa.HistoricoEmArquivo.desligado();
        var conversas = new dev.jarvis.conversa.RegistroConversas(evento -> { });

        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(chamada("esquecer_memoria",
                        json.createObjectNode().put("id", registro.id())))
                .thenReturn(resposta("Posso apagar isso da memória?"));
        var ferramentas = new Ferramentas(List.of(new dev.jarvis.ferramentas.EsquecerMemoriaFerramenta(
                memoria, json, aprovacoes, esquecimentos)), json);
        var assistente = new Assistente(claude, ferramentas,
                new SistemaDoJarvis(new PersonalidadeService(Path.of("nao-existe.md"), "Anderson"),
                        json, props(), memoria),
                json, conversas, new dev.jarvis.conversa.AnexosService(),
                BuscaWebServidor.desligada(), aprovacoes, historico,
                dev.jarvis.aprovacao.ClassificadorDeResposta.semModelo(), null, esquecimentos);

        assistente.responderTelegram(42, 42, 200, "esquece que eu uso Angular no Jarvis");

        assertNotNull(memoria.ler(42, registro.id()), "a chamada do modelo não apaga nada");
        assertEquals("Vou apagar da memória: 'front: Usa Angular'. Posso?",
                aprovacoes.pendenciaDe(42, "telegram-201").orElseThrow());
        assertFalse(conversas.historico(42).trocas().isEmpty(), "a conversa está guardada");

        // "pode" é uma das respostas que a lista de graça do classificador já resolve.
        String resposta = assistente.responderTelegram(42, 42, 201, "pode");

        assertEquals("Esqueci: front.", resposta);
        assertNull(memoria.ler(42, registro.id()));
        assertTrue(conversas.historico(42).trocas().isEmpty(),
                "esquecer a memória continua levando o histórico da conversa junto");
        // Duas chamadas, as duas do primeiro turno (pedido e fala da pergunta). O turno do "pode"
        // não fala com o modelo: ele nunca vê a decisão, só o resultado dela.
        verify(claude, times(2)).criarMensagem(any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class));
    }

    /** Pedido da HUD como o ConversaService o faz: reservado no registro antes de ir ao Assistente. */
    private static dev.jarvis.conversa.OuvinteProgresso daHud(
            dev.jarvis.conversa.RegistroConversas registro, String texto) {
        String id = registro.reservar(7, texto).id();
        return new dev.jarvis.conversa.OuvinteProgresso() {
            @Override public String idPedido() { return id; }
            @Override public long usuarioAutenticado() { return 7; }
            @Override public String canal() { return "hud"; }
        };
    }

    private JsonNode chamada(String nome, ObjectNode entrada) {
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "tool_use");
        resposta.putArray("content").addObject().put("type", "tool_use").put("id", "tu1")
                .put("name", nome).set("input", entrada);
        return resposta;
    }
    private JsonNode resposta(String texto) {
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }
}
