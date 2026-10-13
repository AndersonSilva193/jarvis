package dev.jarvis.ferramentas;

import java.util.Locale;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Diz como está o tempo hoje na cidade configurada: é o que responde "vai chover hoje?". */
@Component
public class ClimaFerramenta implements Ferramenta {

    private final ClimaService clima;
    private final JsonMapper json;
    private final String cidade;

    public ClimaFerramenta(ClimaService clima, JsonMapper json, JarvisProperties props) {
        this.clima = clima;
        this.json = json;
        this.cidade = props.clima().cidade();
    }

    @Override
    public String nome() {
        return "clima_hoje";
    }

    @Override
    public String descricao() {
        return "Mostra o clima de hoje em " + cidade + ": temperatura atual, mínima e máxima, "
                + "chance de chuva e vento. Use para perguntas como 'vai chover hoje?', "
                + "'preciso levar guarda-chuva?' ou 'que temperatura está?'.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        ClimaHoje c = clima.hoje();
        return String.format(Locale.of("pt", "BR"),
                "%s: %.1f °C agora (%s). Mínima %.0f °C, máxima %.0f °C. "
                        + "Chance de chuva hoje: %d%%. Vento: %.0f km/h.",
                c.cidade(), c.temperaturaAtual(), c.descricao(), c.minima(), c.maxima(),
                c.chanceChuvaPct(), c.ventoKmh());
    }
}
