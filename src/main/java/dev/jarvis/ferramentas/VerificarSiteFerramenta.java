package dev.jarvis.ferramentas;

import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.SitesService;
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

    private final SitesService sitesService;
    private final JsonMapper json;

    public VerificarSiteFerramenta(SitesService sitesService, JsonMapper json) {
        this.sitesService = sitesService;
        this.json = json;
    }

    @Override
    public String nome() {
        return "verificar_site";
    }

    @Override
    public String descricao() {
        Map<String, String> sites = sitesService.sites();
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
        sitesService.sites().keySet().forEach(opcoes::add);
        esquema.putArray("required").add("nome");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        Map<String, String> sites = sitesService.sites();
        if (sites.isEmpty()) {
            return "Nenhum site configurado. Adicione em SITES_MONITORADOS no arquivo .env.";
        }
        String escolhido = Json.texto(entrada.path("nome"));
        StringJoiner resultado = new StringJoiner("\n");
        for (Map.Entry<String, String> site : sites.entrySet()) {
            if (escolhido.equals("todos") || escolhido.equals(site.getKey())) {
                resultado.add(site.getKey() + " (" + site.getValue() + "): " + sitesService.checar(site.getValue()));
            }
        }
        return resultado.length() == 0 ? "Site '" + escolhido + "' não está na lista." : resultado.toString();
    }
}
