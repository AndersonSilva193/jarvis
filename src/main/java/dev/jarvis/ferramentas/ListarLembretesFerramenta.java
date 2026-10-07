package dev.jarvis.ferramentas;

import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.lembretes.LembreteService.Lembrete;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class ListarLembretesFerramenta implements Ferramenta {

    private static final DateTimeFormatter EXIBICAO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private final LembreteService lembretes;
    private final JsonMapper json;

    public ListarLembretesFerramenta(LembreteService lembretes, JsonMapper json) {
        this.lembretes = lembretes;
        this.json = json;
    }

    @Override
    public String nome() {
        return "listar_lembretes";
    }

    @Override
    public String descricao() {
        return "Lista os lembretes pendentes, com número, data/hora e texto.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        List<Lembrete> pendentes = lembretes.listar();
        if (pendentes.isEmpty()) {
            return "Nenhum lembrete pendente.";
        }
        StringBuilder sb = new StringBuilder();
        for (Lembrete l : pendentes) {
            sb.append("#").append(l.id()).append(" | ").append(l.quando().format(EXIBICAO))
                    .append(" | ").append(l.texto()).append("\n");
        }
        return sb.toString().trim();
    }
}
