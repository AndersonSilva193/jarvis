package dev.jarvis.briefing.fontes;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Busca o clima do dia na Open-Meteo (https://open-meteo.com), que é aberta e não pede chave.
 * Guarda o resultado por 15 minutos: o tempo não muda a cada pergunta e assim não castigamos a API.
 */
@Service
public class ClimaService {

    private static final Logger log = LoggerFactory.getLogger(ClimaService.class);
    private static final String URL_BASE = "https://api.open-meteo.com/v1/forecast";
    private static final Duration VALIDADE_CACHE = Duration.ofMinutes(15);

    /** O clima de hoje já traduzido: temperaturas em °C, vento em km/h e chance de chuva em %. */
    public record ClimaHoje(
            String cidade,
            double temperaturaAtual,
            double minima,
            double maxima,
            int chanceChuvaPct,
            double ventoKmh,
            String descricao) {
    }

    private record Cache(ClimaHoje dados, Instant quando) {
    }

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Clima config;
    private final String timezone;
    private volatile Cache cache;

    public ClimaService(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.config = props.clima();
        this.timezone = props.zona().getId();
    }

    /** Clima de hoje, vindo do cache quando ele ainda está fresco (15 min). */
    public synchronized ClimaHoje hoje() throws IOException, InterruptedException {
        Cache atual = cache;
        if (atual != null && Duration.between(atual.quando(), Instant.now()).compareTo(VALIDADE_CACHE) < 0) {
            return atual.dados();
        }
        ClimaHoje clima = buscar();
        cache = new Cache(clima, Instant.now());
        return clima;
    }

    private ClimaHoje buscar() throws IOException, InterruptedException {
        // As vírgulas nas listas current/daily vão literais: é o formato que a Open-Meteo espera.
        String url = URL_BASE
                + "?latitude=" + config.latitude()
                + "&longitude=" + config.longitude()
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                + "&timezone=" + URLEncoder.encode(timezone, StandardCharsets.UTF_8)
                + "&forecast_days=1";

        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Jarvis-Clima/1.0")
                .GET()
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new IOException("Open-Meteo respondeu HTTP " + resposta.statusCode() + ": "
                    + resumir(resposta.body()));
        }
        log.info("Clima de {} atualizado na Open-Meteo", config.cidade());
        return interpretar(json.readTree(resposta.body()), config.cidade());
    }

    /**
     * Converte a resposta da Open-Meteo no nosso record. Separado da chamada HTTP para poder ser testado
     * com um JSON fixo. Visível no pacote de propósito: é a parte que o teste exercita.
     */
    static ClimaHoje interpretar(JsonNode raiz, String cidade) throws IOException {
        JsonNode atual = raiz.path("current");
        JsonNode diario = raiz.path("daily");
        int codigo = (int) exigirNumero(atual.path("weather_code"), "current.weather_code");

        return new ClimaHoje(
                cidade,
                exigirNumero(atual.path("temperature_2m"), "current.temperature_2m"),
                exigirNumero(diario.path("temperature_2m_min").path(0), "daily.temperature_2m_min[0]"),
                exigirNumero(diario.path("temperature_2m_max").path(0), "daily.temperature_2m_max[0]"),
                (int) exigirNumero(diario.path("precipitation_probability_max").path(0),
                        "daily.precipitation_probability_max[0]"),
                exigirNumero(atual.path("wind_speed_10m"), "current.wind_speed_10m"),
                descrever(codigo));
    }

    /**
     * Lê um número obrigatório. Campo ausente vira erro em vez de zero: 0 °C é uma temperatura válida,
     * e um default silencioso faria o Jarvis afirmar uma bobagem com toda a confiança.
     */
    private static double exigirNumero(JsonNode no, String caminho) throws IOException {
        if (no == null || !no.isNumber()) {
            throw new IOException("resposta inesperada da Open-Meteo: falta " + caminho);
        }
        return no.doubleValue();
    }

    /** Traduz o código WMO (o "weather_code" da API) para uma descrição curta em português. */
    static String descrever(int codigo) {
        return switch (codigo) {
            case 0 -> "céu limpo";
            case 1 -> "predominantemente limpo";
            case 2 -> "parcialmente nublado";
            case 3 -> "nublado";
            case 45 -> "névoa";
            case 48 -> "névoa com geada";
            case 51 -> "garoa fraca";
            case 53 -> "garoa moderada";
            case 55 -> "garoa forte";
            case 56, 57 -> "garoa congelante";
            case 61 -> "chuva fraca";
            case 63 -> "chuva moderada";
            case 65 -> "chuva forte";
            case 66, 67 -> "chuva congelante";
            case 71 -> "neve fraca";
            case 73 -> "neve moderada";
            case 75 -> "neve forte";
            case 77 -> "grãos de neve";
            case 80 -> "pancadas de chuva fracas";
            case 81 -> "pancadas de chuva moderadas";
            case 82 -> "pancadas de chuva fortes";
            case 85 -> "pancadas de neve fracas";
            case 86 -> "pancadas de neve fortes";
            case 95 -> "trovoada";
            case 96, 99 -> "trovoada com granizo";
            default -> "condição não catalogada (código " + codigo + ")";
        };
    }

    private static String resumir(String corpo) {
        return corpo == null ? "" : corpo.length() > 300 ? corpo.substring(0, 300) + "..." : corpo;
    }
}
