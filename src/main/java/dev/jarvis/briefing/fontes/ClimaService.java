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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Busca o clima na Open-Meteo (https://open-meteo.com), que é aberta e não pede chave.
 *
 * Dois caminhos: {@link #hoje()} traz o tempo agora (com temperatura do momento) e
 * {@link #previsao(LocalDate)} traz a previsão de um dia específico. Tudo com cache, porque o tempo
 * não muda a cada pergunta e não é para castigar uma API gratuita.
 */
@Service
public class ClimaService {

    private static final Logger log = LoggerFactory.getLogger(ClimaService.class);
    private static final String URL_BASE = "https://api.open-meteo.com/v1/forecast";
    private static final Duration VALIDADE_CACHE = Duration.ofMinutes(15);
    /** Previsão de um dia futuro não muda de minuto em minuto. */
    private static final Duration VALIDADE_PREVISAO = Duration.ofHours(1);
    /**
     * Até onde aceitamos pedir previsão. A API permite 16 dias, mas o último dia costuma vir sem
     * temperatura nem código do tempo (conferido: só a chance de chuva vinha preenchida).
     */
    public static final int DIAS_DE_PREVISAO = 15;
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    /** Para a data recusada: sem o ano, "01/01" de 2030 parece logo aí. */
    private static final DateTimeFormatter DIA_MES_ANO = DateTimeFormatter.ofPattern("dd/MM/yyyy");

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

    /**
     * Previsão de um dia. Os campos são objetos (e não primitivos) porque perto do limite do horizonte
     * a API devolve null: melhor dizer "não sei" do que afirmar 0 °C.
     */
    public record PrevisaoDia(
            LocalDate data,
            String cidade,
            Double minima,
            Double maxima,
            Integer chanceChuvaPct,
            Double ventoMaxKmh,
            String descricao) {

        /** Falso quando a previsão para essa data ainda não tem temperatura. */
        public boolean completa() {
            return minima != null && maxima != null;
        }
    }

    private record Cache(ClimaHoje dados, Instant quando) {
    }

    private record CachePrevisao(PrevisaoDia dados, Instant quando) {
    }

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Clima config;
    private final String timezone;
    private final ZoneId zona;
    private volatile Cache cache;
    private final Map<LocalDate, CachePrevisao> cachePrevisao = new ConcurrentHashMap<>();

    public ClimaService(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.config = props.clima();
        this.zona = props.zona();
        this.timezone = this.zona.getId();
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

    /**
     * Previsão de um dia específico (hoje inclusive, mas aí sem a temperatura do momento).
     * Cache de 1 hora por data; datas que já passaram saem do mapa para ele não crescer sem fim.
     */
    public PrevisaoDia previsao(LocalDate data) throws IOException, InterruptedException {
        LocalDate hoje = LocalDate.now(zona);
        validarJanela(data, hoje);
        cachePrevisao.keySet().removeIf(d -> d.isBefore(hoje));

        CachePrevisao guardado = cachePrevisao.get(data);
        if (guardado != null
                && Duration.between(guardado.quando(), Instant.now()).compareTo(VALIDADE_PREVISAO) < 0) {
            return guardado.dados();
        }
        PrevisaoDia previsao = buscarPrevisao(data);
        cachePrevisao.put(data, new CachePrevisao(previsao, Instant.now()));
        return previsao;
    }

    /**
     * Recusa data fora do que a API cobre, antes de gastar uma chamada. A mensagem vai para o modelo,
     * então é em português e já diz qual é a janela possível.
     */
    static void validarJanela(LocalDate data, LocalDate hoje) {
        if (data.isBefore(hoje)) {
            throw new IllegalArgumentException("só faço previsão de hoje (" + hoje.format(DIA_MES)
                    + ") para frente; " + data.format(DIA_MES_ANO) + " já passou");
        }
        LocalDate limite = hoje.plusDays(DIAS_DE_PREVISAO);
        if (data.isAfter(limite)) {
            throw new IllegalArgumentException("só tenho previsão até " + limite.format(DIA_MES)
                    + " (" + DIAS_DE_PREVISAO + " dias à frente); " + data.format(DIA_MES_ANO)
                    + " está longe demais");
        }
    }

    private PrevisaoDia buscarPrevisao(LocalDate data) throws IOException, InterruptedException {
        String url = URL_BASE
                + "?latitude=" + config.latitude()
                + "&longitude=" + config.longitude()
                // No bloco daily o vento é wind_speed_10m_max; wind_speed_10m só existe em current.
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "precipitation_probability_max,wind_speed_10m_max"
                + "&timezone=" + URLEncoder.encode(timezone, StandardCharsets.UTF_8)
                + "&start_date=" + data + "&end_date=" + data;

        JsonNode raiz = chamar(url);
        log.info("Previsão de {} para {} atualizada na Open-Meteo", config.cidade(), data);
        return interpretarPrevisao(raiz, config.cidade(), data);
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

        log.info("Clima de {} atualizado na Open-Meteo", config.cidade());
        return interpretar(chamar(url), config.cidade());
    }

    /** Faz o GET e devolve o JSON. Em erro, usa o campo "reason" da API, que explica o motivo. */
    private JsonNode chamar(String url) throws IOException, InterruptedException {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Jarvis-Clima/1.0")
                .GET()
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200) {
            throw new IOException("Open-Meteo respondeu HTTP " + resposta.statusCode() + ": "
                    + motivoDaApi(resposta.body()));
        }
        return json.readTree(resposta.body());
    }

    /** A Open-Meteo explica a recusa em {"error":true,"reason":"..."}; o JSON cru não ajuda ninguém. */
    private String motivoDaApi(String corpo) {
        try {
            String razao = Json.texto(json.readTree(corpo).path("reason"));
            if (!razao.isBlank()) {
                return razao;
            }
        } catch (Exception ignorado) {
            // corpo não era JSON: cai no resumo bruto
        }
        return resumir(corpo);
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
     * Converte a resposta de um dia específico. Diferente do caminho de hoje, aqui campo ausente vira
     * null em vez de erro: no limite do horizonte a API responde com temperatura e código nulos, e isso
     * é "ainda não sei", não uma resposta malformada.
     */
    static PrevisaoDia interpretarPrevisao(JsonNode raiz, String cidade, LocalDate data) throws IOException {
        JsonNode diario = raiz.path("daily");
        if (diario.isMissingNode() || diario.path("time").isEmpty()) {
            throw new IOException("resposta inesperada da Open-Meteo: falta o bloco daily");
        }
        Double codigo = numeroOuNulo(diario.path("weather_code").path(0));
        Double chance = numeroOuNulo(diario.path("precipitation_probability_max").path(0));

        return new PrevisaoDia(
                data,
                cidade,
                numeroOuNulo(diario.path("temperature_2m_min").path(0)),
                numeroOuNulo(diario.path("temperature_2m_max").path(0)),
                chance == null ? null : (int) Math.round(chance),
                numeroOuNulo(diario.path("wind_speed_10m_max").path(0)),
                codigo == null ? "" : descrever((int) codigo.doubleValue()));
    }

    /** Lê um número opcional: ausente, nulo ou não-numérico viram null. */
    private static Double numeroOuNulo(JsonNode no) {
        return no != null && no.isNumber() ? no.doubleValue() : null;
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
