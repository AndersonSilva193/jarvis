package dev.jarvis.tela;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Procura vídeo e música no YouTube pela Data API v3.
 *
 * <p>Cada {@code search.list} custa 100 das 10.000 unidades diárias de cota — ou seja, umas 100
 * buscas por dia. Por isso o cache de 24 horas por consulta normalizada: "Lo-Fi Para Estudar" e
 * "lo-fi para estudar" são a mesma busca e gastam uma só.
 *
 * <p>A chave nunca entra em log: o que fica registrado é a consulta, o modo e o status HTTP.
 */
@Service
public class YoutubeService {

    private static final Logger log = LoggerFactory.getLogger(YoutubeService.class);

    private static final String URL = "https://www.googleapis.com/youtube/v3/search";
    private static final Duration VALIDADE = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final int MAX_RESULTADOS = 5;
    /** Categoria "Music" do YouTube, usada quando o pedido é de música. */
    private static final String CATEGORIA_MUSICA = "10";

    /** O id de um vídeo tem exatamente 11 caracteres; qualquer outra coisa não vai para a tela. */
    private static final Pattern ID_VALIDO = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    public record Video(String videoId, String titulo, String canal, String miniatura) {
    }

    private record CacheBusca(List<Video> itens, Instant quando) {

        boolean fresco(Instant agora) {
            return Duration.between(quando, agora).compareTo(VALIDADE) < 0;
        }
    }

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties props;
    private final Map<String, CacheBusca> cache = new ConcurrentHashMap<>();

    public YoutubeService(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.props = props;
    }

    /** Se há chave configurada. Sem ela a ferramenta avisa o modelo em vez de falhar. */
    public boolean configurado() {
        return props.youtubeConfigurado();
    }

    /**
     * Os até cinco primeiros resultados da busca, do cache quando ele está fresco.
     *
     * @param modo "video" ou "musica"; em musica a busca é limitada à categoria Music.
     */
    public List<Video> procurar(String consulta, String modo) throws IOException, InterruptedException {
        String chave = chave(consulta, modo);
        CacheBusca guardado = cache.get(chave);
        if (guardado != null && guardado.fresco(Instant.now())) {
            return guardado.itens();
        }
        List<Video> achados = buscar(consulta, modo);
        cache.put(chave, new CacheBusca(achados, Instant.now()));
        return achados;
    }

    /** A chave do cache: o modo mais a consulta sem acento, em minúsculas e sem espaço sobrando. */
    static String chave(String consulta, String modo) {
        return (modo == null || modo.isBlank() ? "video" : modo.trim()) + "|" + Texto.normalizar(consulta);
    }

    private List<Video> buscar(String consulta, String modo) throws IOException, InterruptedException {
        StringBuilder endereco = new StringBuilder(URL)
                .append("?part=snippet&type=video&videoEmbeddable=true")
                .append("&regionCode=BR&relevanceLanguage=pt&safeSearch=moderate")
                .append("&maxResults=").append(MAX_RESULTADOS)
                .append("&q=").append(URLEncoder.encode(consulta, StandardCharsets.UTF_8));
        if ("musica".equals(modo)) {
            endereco.append("&videoCategoryId=").append(CATEGORIA_MUSICA);
        }
        endereco.append("&key=").append(URLEncoder.encode(props.youtube().apiKey(), StandardCharsets.UTF_8));

        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(endereco.toString()))
                .timeout(TIMEOUT)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
        // A URL embute a chave: nunca a logamos, só a consulta, o modo e o status.
        log.info("Busca no YouTube: '{}' ({}) -> HTTP {}", consulta, modo, resposta.statusCode());
        if (resposta.statusCode() == 403) {
            throw new CotaDoYoutubeException();
        }
        if (resposta.statusCode() != 200) {
            throw new IOException("HTTP " + resposta.statusCode());
        }
        return interpretar(json.readTree(resposta.body()));
    }

    /** A cota diária acabou (ou a chave não tem permissão). Vale a pena ter tipo próprio: a saída
     * para o usuário é "tente amanhã", e não "deu erro". */
    public static class CotaDoYoutubeException extends IOException {
        public CotaDoYoutubeException() {
            super("a cota diária do YouTube acabou");
        }
    }

    /**
     * Os vídeos da resposta da API, na ordem em que vieram.
     *
     * Item com id fora do formato é descartado em silêncio: esse id iria para um {@code <iframe>}
     * na HUD, e não é lugar de aceitar o que não reconhecemos.
     */
    static List<Video> interpretar(JsonNode resposta) {
        JsonNode itens = resposta == null ? null : resposta.path("items");
        if (itens == null || !itens.isArray()) {
            return List.of();
        }
        List<Video> videos = new ArrayList<>(itens.size());
        for (int i = 0; i < itens.size(); i++) {
            JsonNode item = itens.get(i);
            String id = Json.texto(item.path("id").path("videoId")).trim();
            if (!ID_VALIDO.matcher(id).matches()) {
                continue;
            }
            JsonNode snippet = item.path("snippet");
            String titulo = Json.texto(snippet.path("title")).trim();
            if (titulo.isEmpty()) {
                continue;
            }
            videos.add(new Video(id, titulo,
                    Json.texto(snippet.path("channelTitle")).trim(),
                    miniaturaDe(snippet.path("thumbnails"))));
        }
        return List.copyOf(videos);
    }

    /** A miniatura média, com a padrão de reserva: nem todo vídeo tem todos os tamanhos. */
    private static String miniaturaDe(JsonNode thumbnails) {
        for (String tamanho : List.of("medium", "high", "default")) {
            String url = Json.texto(thumbnails.path(tamanho).path("url")).trim();
            if (!url.isEmpty()) {
                return url;
            }
        }
        return "";
    }
}
