package dev.jarvis.briefing.fontes;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;

/**
 * Checa se os sites e APIs de SITES_MONITORADOS estão respondendo.
 * Só conhece as URLs da lista: nunca acessa uma URL que venha de fora da configuração.
 */
@Service
public class SitesService {

    /** Resultado da checagem de um site, já em texto pronto para ler. */
    public record Checagem(String nome, String url, String resultado) {
    }

    private final HttpClient http;
    private final Map<String, String> sites;

    public SitesService(HttpClient http, JarvisProperties props) {
        this.http = http;
        this.sites = props.sitesMonitorados();
    }

    /** Mapa nome -> URL dos sites configurados, na ordem do .env. */
    public Map<String, String> sites() {
        return sites;
    }

    /** Checa todos os sites configurados, um a um. */
    public List<Checagem> checarTodos() {
        List<Checagem> checagens = new ArrayList<>();
        for (Map.Entry<String, String> site : sites.entrySet()) {
            checagens.add(new Checagem(site.getKey(), site.getValue(), checar(site.getValue())));
        }
        return checagens;
    }

    /** Faz uma requisição GET e descreve o resultado (status, tempo e situação) em uma linha. */
    public String checar(String url) {
        long inicio = System.nanoTime();
        try {
            HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("User-Agent", "Jarvis-Monitor/1.0")
                    .GET()
                    .build();
            int status = http.send(requisicao, HttpResponse.BodyHandlers.discarding()).statusCode();
            long ms = (System.nanoTime() - inicio) / 1_000_000;
            String situacao = status < 400 ? "OK" : status < 500 ? "respondeu, mas com erro do cliente" : "ERRO no servidor";
            return "HTTP " + status + " em " + ms + " ms (" + situacao + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "verificação interrompida";
        } catch (Exception e) {
            return "FORA DO AR ou inacessível (" + e.getClass().getSimpleName() + ": " + e.getMessage() + ")";
        }
    }
}
