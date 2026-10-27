package dev.jarvis.ferramentas;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import dev.jarvis.briefing.fontes.ClimaService.PrevisaoDia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Diz como está o tempo hoje ou como fica em um dia dos próximos: é o que responde
 * "vai chover hoje?" e "como fica o tempo no sábado?".
 */
@Component
public class ClimaFerramenta implements Ferramenta {

    private static final Locale BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter DIA_DA_SEMANA = DateTimeFormatter.ofPattern("EEEE, dd/MM", BR);

    private final ClimaService clima;
    private final JsonMapper json;
    private final String cidade;
    private final ZoneId zona;

    public ClimaFerramenta(ClimaService clima, JsonMapper json, JarvisProperties props) {
        this.clima = clima;
        this.json = json;
        this.cidade = props.clima().cidade();
        this.zona = props.zona();
    }

    @Override
    public String nome() {
        return "clima";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.INFORMACAO; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Mostra o clima em " + cidade + ": temperatura, mínima e máxima, chance de chuva e vento. "
                + "Sem data, responde sobre hoje (incluindo a temperatura neste momento). Com data, dá a "
                + "previsão daquele dia, até " + ClimaService.DIAS_DE_PREVISAO + " dias à frente. "
                + "Use para 'vai chover hoje?', 'preciso de guarda-chuva?', 'como fica o tempo no sábado?' "
                + "ou 'vai chover na quinta?'.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode data = esquema.putObject("properties").putObject("data");
        data.put("type", "string");
        data.put("description", "Dia desejado no formato AAAA-MM-DD, ex.: 2026-10-17. Deixe vazio para hoje. "
                + "Calcule a data a partir do horário atual informado no contexto (de hoje até "
                + ClimaService.DIAS_DE_PREVISAO + " dias à frente).");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        String pedida = entrada == null ? "" : Json.texto(entrada.path("data")).trim();
        if (pedida.isEmpty()) {
            return paraTexto(clima.hoje());
        }

        LocalDate data;
        try {
            data = LocalDate.parse(pedida);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("data inválida: '" + pedida + "'. Use o formato AAAA-MM-DD");
        }
        if (data.equals(LocalDate.now(zona))) {
            return paraTexto(clima.hoje());
        }
        return paraTexto(clima.previsao(data));
    }

    /** Hoje: o destaque é a temperatura de agora. */
    private String paraTexto(ClimaHoje c) {
        return String.format(BR,
                "%s: %.1f °C agora (%s). Mínima %.0f °C, máxima %.0f °C. "
                        + "Chance de chuva hoje: %d%%. Vento: %.0f km/h.",
                c.cidade(), c.temperaturaAtual(), c.descricao(), c.minima(), c.maxima(),
                c.chanceChuvaPct(), c.ventoKmh());
    }

    /** Outro dia: sem temperatura "de agora", e omitindo o que a previsão ainda não sabe. */
    private String paraTexto(PrevisaoDia p) {
        StringBuilder sb = new StringBuilder(p.data().format(DIA_DA_SEMANA) + " em " + p.cidade() + ": ");
        StringJoiner partes = new StringJoiner(", ");
        if (p.completa()) {
            partes.add(String.format(BR, "mínima %.0f °C e máxima %.0f °C", p.minima(), p.maxima()));
        }
        if (!p.descricao().isBlank()) {
            partes.add(p.descricao());
        }
        if (p.chanceChuvaPct() != null) {
            partes.add("chance de chuva " + p.chanceChuvaPct() + "%");
        }
        if (p.ventoMaxKmh() != null) {
            partes.add(String.format(BR, "vento até %.0f km/h", p.ventoMaxKmh()));
        }
        if (partes.length() == 0) {
            return sb + "a previsão ainda não alcança essa data.";
        }
        sb.append(partes);
        if (!p.completa()) {
            sb.append(" (a previsão para esse dia ainda não tem temperatura)");
        }
        return sb.append(".").toString();
    }
}
