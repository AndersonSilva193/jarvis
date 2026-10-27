package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O JSON que o {@link ClaudeClient} realmente mandou, lido de volta do {@code BodyPublisher} de um
 * {@code HttpClient} mockado.
 *
 * <p>É o único jeito de conferir o corpo do pedido — cache de prompt e esforço de saída são campos
 * que nenhum teste dos montadores vê, porque quem os põe é o cliente. Mora numa classe só porque
 * duas cópias de uma leitura reativa de {@code Flow.Subscriber} divergiriam na primeira correção
 * feita num lado só.
 */
final class CorpoDoPedido {

    private CorpoDoPedido() {
    }

    /** O corpo da única chamada feita àquele cliente mockado. */
    static JsonNode unico(java.net.http.HttpClient http, JsonMapper json) throws Exception {
        List<JsonNode> corpos = todos(http, json);
        assertTrue(corpos.size() == 1, "esperava uma chamada só, houve " + corpos.size());
        return corpos.getFirst();
    }

    /** Os corpos de todas as chamadas, na ordem em que saíram. */
    @SuppressWarnings("unchecked")
    static List<JsonNode> todos(java.net.http.HttpClient http, JsonMapper json) throws Exception {
        ArgumentCaptor<HttpRequest> pedidos = ArgumentCaptor.forClass(HttpRequest.class);
        Mockito.verify(http, Mockito.atLeastOnce())
                .send(pedidos.capture(), any(HttpResponse.BodyHandler.class));
        List<JsonNode> corpos = new ArrayList<>();
        for (HttpRequest pedido : pedidos.getAllValues()) {
            corpos.add(json.readTree(ler(pedido)));
        }
        return corpos;
    }

    private static String ler(HttpRequest pedido) throws Exception {
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
