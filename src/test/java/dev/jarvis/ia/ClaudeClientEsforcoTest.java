package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * O {@code output_config.effort} que sai no corpo do pedido, conferido no <b>JSON da requisição</b>.
 *
 * <p>É irmão do {@code ClaudeClientCacheTest} e existe pelo mesmo motivo: o esforço é um campo que
 * só o cliente põe, então nenhum teste de quem monta blocos ou mensagens o veria. E ele vale
 * dinheiro — é o token de saída, o lado caro da conta.
 */
@SuppressWarnings("unchecked")
class ClaudeClientEsforcoTest {

    private static final String RESPOSTA = """
            {"usage":{"input_tokens":10,"output_tokens":5},
             "content":[{"type":"text","text":"pronto"}],"stop_reason":"end_turn"}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private final java.net.http.HttpClient http = mock(java.net.http.HttpClient.class);
    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> avisos;

    /** Os cinco padrões do {@code application.yml}, que é o que roda na VPS. */
    private static final JarvisProperties.Anthropic.Esforcos PADROES =
            new JarvisProperties.Anthropic.Esforcos("medium", "low", "low", "low", "medium");

    private JarvisProperties props(JarvisProperties.Anthropic anthropic) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, anthropic,
                null, null, null, null, null,
                new JarvisProperties.BuscaWeb(false, "web_search_20250305", 3), null, null);
    }

    private ClaudeClient cliente(JarvisProperties.Anthropic.Esforcos esforcos) {
        return new ClaudeClient(http, json, props(
                new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512, "5m", esforcos)),
                medidor);
    }

    private void respondendo() throws IOException, InterruptedException {
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(200);
        when(resposta.body()).thenReturn(RESPOSTA);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resposta);
    }

    /** Começa a ouvir o logger do cliente. Chamado <b>antes</b> do construtor: o WARN sai lá. */
    private void ouvirAvisos() {
        logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ClaudeClient.class);
        avisos = new ListAppender<>();
        avisos.start();
        logger.addAppender(avisos);
    }

    @AfterEach
    void soltarOApendice() {
        if (logger != null && avisos != null) {
            logger.detachAppender(avisos);
        }
    }

    private List<String> warns() {
        return avisos.list.stream()
                .filter(evento -> evento.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private String esforcoDe(JsonNode corpo) {
        return corpo.path("output_config").path("effort").stringValue();
    }

    @Test
    @DisplayName("cada origem leva o esforço configurado para ela, e a tarefa leva o dela")
    void cadaOrigemLevaOSeuEsforco() throws Exception {
        respondendo();
        ClaudeClient cliente = cliente(PADROES);
        ArrayNode vazio = json.createArrayNode();

        cliente.criarMensagem(Origem.CONVERSA, vazio, json.createArrayNode(), vazio);
        cliente.criarMensagem(Origem.BRIEFING, vazio, json.createArrayNode(), vazio);
        cliente.criarMensagem(Origem.INICIALIZACAO, vazio, json.createArrayNode(), vazio);
        // O classificador e o anotador usam a sobrecarga curta, com teto e prazo próprios.
        cliente.criarMensagem(Origem.CLASSIFICADOR, vazio, json.createArrayNode(), vazio,
                60, Duration.ofSeconds(5));
        cliente.criarMensagem(Origem.ANOTADOR, vazio, json.createArrayNode(), vazio,
                512, Duration.ofSeconds(20));
        // A tarefa agendada gasta na Origem.CONVERSA e pensa com o ESFORCO_TAREFA: é o único
        // caminho em que a origem não decide, e por isso o perfil vai dito na chamada.
        cliente.criarMensagem(Origem.CONVERSA, PerfilDeEsforco.TAREFA, vazio,
                json.createArrayNode(), vazio);

        List<JsonNode> corpos = CorpoDoPedido.todos(http, json);
        assertEquals(List.of("medium", "low", "low", "low", "low", "medium"),
                corpos.stream().map(this::esforcoDe).toList());
    }

    @Test
    @DisplayName("sem o bloco de esforços o corpo fica igual ao de antes desta configuração")
    void semConfiguracaoOCorpoNaoMuda() throws Exception {
        respondendo();
        // A aridade de antes do esforço por origem: é o que os testes antigos montam.
        new ClaudeClient(http, json,
                props(new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512, "5m")),
                medidor).criarMensagem(Origem.CONVERSA, json.createArrayNode(),
                        json.createArrayNode(), json.createArrayNode());

        JsonNode corpo = CorpoDoPedido.unico(http, json);
        assertTrue(corpo.path("output_config").isMissingNode(),
                "sem configuração o campo não pode aparecer: um byte novo no corpo custa o cache "
                        + "de prompt quente de todo mundo");
        assertEquals(Set.of("model", "max_tokens", "system", "messages", "cache_control"),
                corpo.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet()),
                "o corpo tem de ter exatamente as chaves de antes — nem uma a mais");
    }

    @Test
    @DisplayName("valor torto no .env cai no padrão do perfil, com um WARN na subida")
    void valorTortoCaiNoPadraoComAviso() throws Exception {
        respondendo();
        ouvirAvisos();
        ClaudeClient cliente = cliente(new JarvisProperties.Anthropic.Esforcos(
                "medium", "médio", "low", "low", "medium"));

        List<String> warns = warns();
        assertEquals(1, warns.size(), "um WARN, e na subida: a cada pedido seria ruído");
        assertTrue(warns.getFirst().contains("ESFORCO_BRIEFING"),
                "o aviso tem de nomear a variável torta, senão não ajuda ninguém: " + warns);
        assertTrue(warns.getFirst().contains("médio"), "e o valor recusado: " + warns);

        cliente.criarMensagem(Origem.BRIEFING, json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode());
        assertEquals("low", esforcoDe(CorpoDoPedido.unico(http, json)),
                "um valor torto não pode virar um HTTP 400 a cada pedido");
    }

    @Test
    @DisplayName("campo em branco é o padrão do perfil, e em silêncio")
    void brancoEhOPadraoSemAviso() throws Exception {
        respondendo();
        ouvirAvisos();
        ClaudeClient cliente = cliente(
                new JarvisProperties.Anthropic.Esforcos("", null, null, null, null));

        // Branco é "não mexi nisso", não erro de digitação: só texto que não casa nível nenhum
        // merece aviso.
        assertTrue(warns().isEmpty(), "em branco não é valor torto: " + warns());
        cliente.criarMensagem(Origem.CONVERSA, json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode());
        assertEquals("medium", esforcoDe(CorpoDoPedido.unico(http, json)));
    }

    @Test
    @DisplayName("o esforço não muda entre os passos de um mesmo turno")
    void oEsforcoNaoMudaNoMeioDoTurno() throws Exception {
        respondendo();
        ClaudeClient cliente = cliente(PADROES);
        ArrayNode vazio = json.createArrayNode();
        // Oito passos do laço de ferramentas. Se o nível oscilasse, cada passo recomeçaria o
        // prefixo que o anterior acabou de gravar — e o cache automático da conversa existe
        // justamente para o passo seguinte ler o que o anterior escreveu.
        for (int passo = 0; passo < 8; passo++) {
            cliente.criarMensagem(Origem.CONVERSA, vazio, json.createArrayNode(), vazio);
        }
        assertTrue(CorpoDoPedido.todos(http, json).stream()
                .allMatch(corpo -> "medium".equals(esforcoDe(corpo))));
        assertFalse(CorpoDoPedido.todos(http, json).isEmpty());
        Mockito.verify(http, Mockito.times(8))
                .send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }
}
