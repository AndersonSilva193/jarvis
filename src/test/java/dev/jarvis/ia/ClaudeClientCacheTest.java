package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * O que sai no corpo do pedido quanto a cache de prompt — conferido no <b>JSON da requisição</b>, e
 * não só no retorno de quem monta os blocos.
 *
 * <p>Existe porque os dois marcadores têm de combinar: o explícito, no bloco fixo do {@code system},
 * e o automático, na chave de topo. Se os TTLs divergirem a API devolve HTTP 400, e nenhum teste de
 * unidade dos montadores veria isso — só o corpo montado vê.
 */
@SuppressWarnings("unchecked")
class ClaudeClientCacheTest {

    /** Grande o bastante para passar do mínimo cacheável: o estimador é chars/3 e o mínimo é 512. */
    private static final String PERSONALIDADE = "# Jarvis\n\n"
            + "Trate o Anderson por senhor. Seja breve e exato. ".repeat(60);

    private static final String RESPOSTA = """
            {"usage":{"input_tokens":10,"output_tokens":5,
             "cache_creation_input_tokens":0,"cache_read_input_tokens":0},
             "content":[{"type":"text","text":"pronto"}],"stop_reason":"end_turn"}
            """;

    private final JsonMapper json = JsonMapper.builder().build();
    private final java.net.http.HttpClient http = mock(java.net.http.HttpClient.class);
    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);

    private JarvisProperties props(String ttl) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512, ttl),
                null, null, null, null, null,
                new JarvisProperties.BuscaWeb(false, "web_search_20250305", 3), null, null);
    }

    /** O corpo que o cliente realmente mandou, lido de volta do BodyPublisher. */
    private JsonNode corpoEnviado() throws Exception {
        ArgumentCaptor<HttpRequest> pedido = ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(http).send(pedido.capture(), any(HttpResponse.BodyHandler.class));
        return json.readTree(lerCorpo(pedido.getValue()));
    }

    private static String lerCorpo(HttpRequest pedido) throws Exception {
        HttpRequest.BodyPublisher publisher = pedido.bodyPublisher().orElseThrow();
        List<ByteBuffer> partes = new ArrayList<>();
        CountDownLatch pronto = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription assinatura) {
                assinatura.request(Long.MAX_VALUE);
            }
            @Override public void onNext(ByteBuffer parte) { partes.add(parte); }
            @Override public void onError(Throwable erro) { pronto.countDown(); }
            @Override public void onComplete() { pronto.countDown(); }
        });
        assertTrue(pronto.await(5, TimeUnit.SECONDS), "o BodyPublisher não terminou");
        int total = partes.stream().mapToInt(ByteBuffer::remaining).sum();
        ByteBuffer inteiro = ByteBuffer.allocate(total);
        partes.forEach(inteiro::put);
        return new String(inteiro.array(), StandardCharsets.UTF_8);
    }

    private void respondendo() throws IOException, InterruptedException {
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(200);
        when(resposta.body()).thenReturn(RESPOSTA);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(resposta);
    }

    /** Os blocos de system de verdade, montados pelo caminho único do projeto. */
    private ArrayNode system(JarvisProperties props, Path pasta) throws IOException {
        Path arquivo = pasta.resolve("personalidade.md");
        Files.writeString(arquivo, PERSONALIDADE);
        var sistema = new SistemaDoJarvis(new PersonalidadeService(arquivo, "Anderson"), json, props);
        return sistema.blocos(Canal.TELEGRAM);
    }

    @Test
    @DisplayName("o pedido da conversa leva cache_control nos dois lugares: bloco fixo e nível de pedido")
    void cacheNosDoisLugares(@TempDir Path pasta) throws Exception {
        respondendo();
        JarvisProperties props = props("5m");
        ArrayNode system = system(props, pasta);
        new ClaudeClient(http, json, props, medidor).criarMensagem(Origem.CONVERSA, system,
                json.createArrayNode(), json.createArrayNode());

        JsonNode corpo = corpoEnviado();
        assertEquals("ephemeral", corpo.path("cache_control").path("type").stringValue(),
                "sem o cache_control de topo o laço de ferramentas recobra tudo a cada passo");
        assertEquals("ephemeral",
                corpo.path("system").get(0).path("cache_control").path("type").stringValue(),
                "o bloco fixo é a personalidade; é ele que paga uma vez só");
        assertTrue(corpo.path("system").get(1).path("cache_control").isMissingNode(),
                "o bloco dinâmico tem a hora: cachear ele pagaria a escrita à toa");
    }

    @Test
    @DisplayName("com TTL de 1h os dois marcadores levam o mesmo ttl; no padrão nenhum leva")
    void ttlCombinaNosDoisMarcadores(@TempDir Path pasta) throws Exception {
        respondendo();
        JarvisProperties umaHora = props("1h");
        new ClaudeClient(http, json, umaHora, medidor).criarMensagem(Origem.CONVERSA,
                system(umaHora, pasta), json.createArrayNode(), json.createArrayNode());

        JsonNode corpo = corpoEnviado();
        // A API exige que a entrada de TTL mais longo venha antes da de TTL mais curto. Marcadores
        // com TTLs diferentes no mesmo pedido são HTTP 400 — por isso os dois saem do mesmo lugar.
        assertEquals("1h", corpo.path("cache_control").path("ttl").stringValue());
        assertEquals("1h", corpo.path("system").get(0).path("cache_control").path("ttl")
                .stringValue());
    }

    @Test
    @DisplayName("no padrão de 5m o campo ttl não é emitido em nenhum dos dois")
    void padraoNaoEmiteTtl(@TempDir Path pasta) throws Exception {
        respondendo();
        JarvisProperties props = props("5m");
        new ClaudeClient(http, json, props, medidor).criarMensagem(Origem.CONVERSA,
                system(props, pasta), json.createArrayNode(), json.createArrayNode());

        JsonNode corpo = corpoEnviado();
        assertTrue(corpo.path("cache_control").path("ttl").isMissingNode());
        assertTrue(corpo.path("system").get(0).path("cache_control").path("ttl").isMissingNode(),
                "5m é o padrão da API: omitir mantém o corpo igual ao de antes, e ninguém perde o "
                        + "cache quente num deploy");
    }

    @Test
    @DisplayName("quem chama o modelo uma vez só não leva o cache_control de topo")
    void chamadaUnicaNaoEscreveCacheDaConversa(@TempDir Path pasta) throws Exception {
        respondendo();
        JarvisProperties props = props("5m");
        // Briefing, introdução da HUD, classificador e anotador fazem uma chamada e vão embora:
        // a escrita custaria 1,25x e nenhuma leitura viria depois para pagá-la.
        new ClaudeClient(http, json, props, medidor).criarMensagem(Origem.BRIEFING,
                system(props, pasta), json.createArrayNode(), json.createArrayNode());

        JsonNode corpo = corpoEnviado();
        assertTrue(corpo.path("cache_control").isMissingNode());
        assertEquals("ephemeral",
                corpo.path("system").get(0).path("cache_control").path("type").stringValue(),
                "o bloco fixo continua cacheado: é o prefixo que ela compartilha com a conversa");
    }

    @Test
    @DisplayName("um valor torto de TTL cai no padrão em vez de virar 400 a cada pedido")
    void ttlTortoCaiNoPadrao() {
        assertEquals("5m", props("sei lá").anthropic().ttlDeCache());
        assertEquals("5m", props(null).anthropic().ttlDeCache());
        assertEquals("1h", props(" 1H ").anthropic().ttlDeCache());
    }
}
