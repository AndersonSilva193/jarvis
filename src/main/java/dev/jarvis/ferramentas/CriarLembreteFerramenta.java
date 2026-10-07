package dev.jarvis.ferramentas;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CriarLembreteFerramenta implements Ferramenta {

    private static final DateTimeFormatter EXIBICAO = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    private final LembreteService lembretes;
    private final JsonMapper json;
    private final ZoneId zona;

    public CriarLembreteFerramenta(LembreteService lembretes, JsonMapper json, JarvisProperties props) {
        this.lembretes = lembretes;
        this.json = json;
        this.zona = props.zona();
    }

    @Override
    public String nome() {
        return "criar_lembrete";
    }

    @Override
    public String descricao() {
        return "Agenda um lembrete que será enviado no Telegram na data e hora indicadas. "
                + "Calcule a data e hora exatas a partir do horário atual informado no contexto.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode props = esquema.putObject("properties");

        ObjectNode texto = props.putObject("texto");
        texto.put("type", "string");
        texto.put("description", "O que deve ser lembrado, curto e claro.");

        ObjectNode dataHora = props.putObject("data_hora");
        dataHora.put("type", "string");
        dataHora.put("description", "Data e hora locais no formato AAAA-MM-DDTHH:MM, ex.: 2026-10-06T15:30");

        esquema.putArray("required").add("texto").add("data_hora");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        String texto = Json.texto(entrada.path("texto")).trim();
        String dataHora = Json.texto(entrada.path("data_hora")).trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("o texto do lembrete está vazio");
        }
        ZonedDateTime quando;
        try {
            quando = LocalDateTime.parse(dataHora).atZone(zona);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("data_hora inválida: '" + dataHora + "'. Use AAAA-MM-DDTHH:MM");
        }
        var lembrete = lembretes.agendar(texto, quando);
        return "Lembrete #" + lembrete.id() + " agendado para " + quando.format(EXIBICAO) + ": " + texto;
    }
}
