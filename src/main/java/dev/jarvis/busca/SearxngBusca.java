package dev.jarvis.busca;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A busca num SearXNG próprio, pelo formato JSON dele.
 *
 * <p>O endereço é o {@code SEARXNG_URL}, configuração do dono — pode ser interno à rede do Docker
 * ({@code http://searxng:8080}), e por isso não passa pelo {@code GuardaSsrf}: o modelo só escolhe
 * a consulta, nunca para onde ela vai. O SearXNG precisa ter {@code json} em
 * {@code search.formats} no {@code settings.yml}; sem isso ele responde 403, que vira falha e passa
 * a vez ao próximo provedor.
 */
@Component
public class SearxngBusca implements ProvedorDeBusca {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties props;

    public SearxngBusca(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.props = props;
    }

    @Override
    public String nome() {
        return "searxng";
    }

    @Override
    public boolean configurado() {
        return !props.urlDoSearxng().isEmpty();
    }

    @Override
    public RespostaDaBusca buscar(Consulta consulta) throws FalhaDeBusca, InterruptedException {
        StringBuilder endereco = new StringBuilder(props.urlDoSearxng())
                .append("/search?q=").append(URLEncoder.encode(consulta.texto(), StandardCharsets.UTF_8))
                .append("&format=json&language=pt-BR&safesearch=1");
        String faixa = faixaDeTempo(consulta.recenciaDias());
        if (!faixa.isEmpty()) {
            endereco.append("&time_range=").append(faixa);
        }
        HttpRequest pedido;
        try {
            pedido = HttpRequest.newBuilder(URI.create(endereco.toString()))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
        } catch (IllegalArgumentException e) {
            throw new FalhaDeBusca("url_invalida");
        }
        HttpResponse<String> resposta;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FalhaDeBusca("rede");
        }
        if (resposta == null) {
            throw new FalhaDeBusca("rede");
        }
        if (resposta.statusCode() != 200) {
            throw new FalhaDeBusca("http_" + resposta.statusCode());
        }
        JsonNode raiz;
        try {
            raiz = json.readTree(resposta.body());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new FalhaDeBusca("resposta_invalida");
        }
        List<ResultadoDaBusca> resultados = new ArrayList<>();
        for (JsonNode item : raiz.path("results")) {
            if (resultados.size() >= consulta.quantidade()) {
                break;
            }
            resultados.add(new ResultadoDaBusca(Json.texto(item.path("title")),
                    Json.texto(item.path("url")), Json.texto(item.path("publishedDate")),
                    Json.texto(item.path("content"))));
        }
        return new RespostaDaBusca(nome(), resultados, "");
    }

    /**
     * O SearXNG só conhece quatro faixas; a recência pedida cai na menor que a contém. Pedir "3
     * dias" e receber a semana traz um pouco a mais, nunca a menos.
     */
    static String faixaDeTempo(int dias) {
        if (dias <= 0) return "";
        if (dias <= 1) return "day";
        if (dias <= 7) return "week";
        if (dias <= 31) return "month";
        return "year";
    }
}
