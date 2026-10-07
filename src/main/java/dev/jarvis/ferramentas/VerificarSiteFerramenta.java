package dev.jarvis.ferramentas;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Verifica se os sites/APIs configurados em SITES_MONITORADOS estão respondendo.
 * Só acessa URLs da lista: o modelo escolhe pelo nome, nunca passa uma URL qualquer.
 */
@Component
public class VerificarSiteFerramenta implements Ferramenta {

    private final HttpClient http;
    private final JsonMapper json;
    private final Map<String, String> sites;

    public VerificarSiteFerramenta(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.sites = props.sitesMonitorados();
    }

    @Override
    public String nome() {
        return "verificar_site";
    }

    @Override
    public String descricao() {
        return "Verifica se os sites e APIs monitorados estão no ar, retornando o status HTTP e o tempo de "
                + "resposta. Sites disponíveis: " + (sites.isEmpty() ? "nenhum configurado" : sites.keySet())
                + ". Use nome='todos' para checar todos de uma vez.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode nome = esquema.putObject("properties").putObject("nome");
        nome.put("type", "string");
        nome.put("description", "Nome do site a verificar, ou 'todos'.");
        var opcoes = nome.putArray("enum").add("todos");
        sites.keySet().forEach(opcoes::add);
        esquema.putArray("required").add("nome");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        if (sites.isEmpty()) {
            return "Nenhum site configurado. Adicione em SITES_MONITORADOS no arquivo .env.";
        }
        String escolhido = Json.texto(entrada.path("nome"));
        StringJoiner resultado = new StringJoiner("\n");
        for (Map.Entry<String, String> site : sites.entrySet()) {
            if (escolhido.equals("todos") || escolhido.equals(site.getKey())) {
                resultado.add(site.getKey() + " (" + site.getValue() + "): " + checar(site.getValue()));
            }
        }
        return resultado.length() == 0 ? "Site '" + escolhido + "' não está na lista." : resultado.toString();
    }

    private String checar(String url) {
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
