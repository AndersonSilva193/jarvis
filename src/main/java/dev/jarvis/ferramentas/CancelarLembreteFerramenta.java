package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CancelarLembreteFerramenta implements Ferramenta {

    private final LembreteService lembretes;
    private final JsonMapper json;

    public CancelarLembreteFerramenta(LembreteService lembretes, JsonMapper json) {
        this.lembretes = lembretes;
        this.json = json;
    }

    @Override
    public String nome() {
        return "cancelar_lembrete";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.LEMBRETES; }
    @Override public boolean simples() { return false; }

    @Override
    public String descricao() {
        return "Cancela um lembrete pendente pelo número. Se não souber o número, use "
                + "listar_lembretes antes. Em lembrete recorrente, cancela a série inteira: "
                + "não existe cancelar só uma ocorrência.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode id = esquema.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.put("description", "Número do lembrete (o que aparece depois do #).");
        esquema.putArray("required").add("id");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        int id = (int) Json.numero(entrada.path("id"));
        return lembretes.cancelar(id)
                ? "Lembrete #" + id + " cancelado."
                : "Não encontrei lembrete pendente com o número " + id + ".";
    }
}
