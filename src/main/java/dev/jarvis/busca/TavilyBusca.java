package dev.jarvis.busca;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A busca e a extração de página do Tavily, por HTTP direto.
 *
 * <p>Busca sempre na profundidade {@code basic}, que custa 1 crédito; a {@code advanced} custa dois
 * e não muda o que um assistente de voz precisa de uma busca. A chave vai no cabeçalho
 * {@code Authorization} e não aparece em log nem em mensagem de falha.
 */
@Component
public class TavilyBusca implements ProvedorDeBusca {

    static final URI BUSCA = URI.create("https://api.tavily.com/search");
    static final URI EXTRACAO = URI.create("https://api.tavily.com/extract");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** A extração do Tavily tem 10 s de prazo do lado de lá; aqui sobra a ida e a volta. */
    private static final Duration TIMEOUT_EXTRACAO = Duration.ofSeconds(15);

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties props;
    private final Clock relogio;

    @Autowired
    public TavilyBusca(HttpClient http, JsonMapper json, JarvisProperties props) {
        this(http, json, props, Clock.systemUTC());
    }

    /** O relógio entra por aqui nos testes, para o {@code start_date} ser previsível. */
    TavilyBusca(HttpClient http, JsonMapper json, JarvisProperties props, Clock relogio) {
        this.http = http;
        this.json = json;
        this.props = props;
        this.relogio = relogio;
    }

    @Override
    public String nome() {
        return "tavily";
    }

    @Override
    public boolean configurado() {
        return !props.chaveDoTavily().isEmpty();
    }

    @Override
    public RespostaDaBusca buscar(Consulta consulta) throws FalhaDeBusca, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("query", consulta.texto());
        corpo.put("search_depth", "basic");
        corpo.put("max_results", consulta.quantidade());
        corpo.put("topic", "general");
        // Sem isto o published_date só vem no topic "news", e a data é metade da citação.
        corpo.put("include_published_date", true);
        if (consulta.recenciaDias() > 0) {
            corpo.put("start_date", LocalDate.ofInstant(relogio.instant(), props.zona())
                    .minusDays(consulta.recenciaDias()).toString());
        }
        JsonNode raiz = enviar(BUSCA, corpo, TIMEOUT);
        List<ResultadoDaBusca> resultados = new ArrayList<>();
        for (JsonNode item : raiz.path("results")) {
            resultados.add(new ResultadoDaBusca(Json.texto(item.path("title")),
                    Json.texto(item.path("url")), Json.texto(item.path("published_date")),
                    Json.texto(item.path("content"))));
        }
        return new RespostaDaBusca(nome(), resultados, "");
    }

    /**
     * O texto principal de uma página. O Tavily responde 200 mesmo quando a página falhou, com ela
     * em {@code failed_results} — por isso o vazio também é falha.
     */
    public String extrair(String url) throws FalhaDeBusca, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.putArray("urls").add(url);
        corpo.put("extract_depth", "basic");
        corpo.put("format", "text");
        JsonNode raiz = enviar(EXTRACAO, corpo, TIMEOUT_EXTRACAO);
        for (JsonNode item : raiz.path("results")) {
            String texto = Json.texto(item.path("raw_content")).strip();
            if (!texto.isEmpty()) {
                return texto;
            }
        }
        throw new FalhaDeBusca(raiz.path("failed_results").isEmpty() ? "pagina_vazia" : "extracao_falhou");
    }

    private JsonNode enviar(URI destino, ObjectNode corpo, Duration prazo)
            throws FalhaDeBusca, InterruptedException {
        HttpRequest pedido = HttpRequest.newBuilder(destino)
                .timeout(prazo)
                .header("Authorization", "Bearer " + props.chaveDoTavily())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();
        HttpResponse<String> resposta;
        try {
            resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new FalhaDeBusca("rede");
        }
        if (resposta == null) {
            throw new FalhaDeBusca("rede");
        }
        // 432 é o limite do plano: a cota acabou do lado de lá antes da nossa conta perceber.
        if (resposta.statusCode() != 200) {
            throw new FalhaDeBusca("http_" + resposta.statusCode());
        }
        try {
            return json.readTree(resposta.body());
        } catch (JacksonException | IllegalArgumentException e) {
            throw new FalhaDeBusca("resposta_invalida");
        }
    }
}
