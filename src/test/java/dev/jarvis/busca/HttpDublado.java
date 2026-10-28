package dev.jarvis.busca;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import dev.jarvis.config.JarvisProperties;

/**
 * O {@code HttpClient} de mentira dos testes de busca, e as properties com o bloco de busca.
 *
 * <p>As respostas imitam a forma das APIs reais (o {@code results} do Tavily e do SearXNG): um
 * falso que respondesse com a chave que o código espera só provaria que o código concorda com o
 * próprio engano.
 */
public final class HttpDublado {

    private HttpDublado() {
    }

    public static JarvisProperties props(String provedores, String tavily, String searxng, int cota) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 42L),
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                new JarvisProperties.Clima("Curitiba", -25.4284, -49.2733),
                null, null, null, null,
                new JarvisProperties.BuscaWeb(true, "web_search_20250305", 3, provedores, tavily,
                        searxng, cota),
                null, null);
    }

    @SuppressWarnings("unchecked")
    public static HttpResponse<String> resposta(int status, String corpo) {
        HttpResponse<String> resposta = mock(HttpResponse.class);
        when(resposta.statusCode()).thenReturn(status);
        when(resposta.body()).thenReturn(corpo);
        return resposta;
    }

    /** Um cliente que devolve as respostas na ordem, repetindo a última. */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    public static HttpClient respondendo(HttpResponse<String>... respostas) throws Exception {
        HttpClient http = mock(HttpClient.class);
        var stub = when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)));
        for (HttpResponse<String> resposta : respostas) {
            stub = stub.thenReturn(resposta);
        }
        return http;
    }

    /** Os pedidos feitos, na ordem. */
    @SuppressWarnings("unchecked")
    public static List<HttpRequest> pedidos(HttpClient http) throws Exception {
        ArgumentCaptor<HttpRequest> pedidos = ArgumentCaptor.forClass(HttpRequest.class);
        Mockito.verify(http, Mockito.atLeastOnce()).send(pedidos.capture(), any(HttpResponse.BodyHandler.class));
        return pedidos.getAllValues();
    }

    /** O corpo de um pedido, lido de volta do {@code BodyPublisher}. */
    public static String corpo(HttpRequest pedido) throws Exception {
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
}
