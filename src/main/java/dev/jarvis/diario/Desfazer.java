package dev.jarvis.diario;

import java.util.List;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Capacidades de reversão emitidas pelo servidor. Nunca recebem conteúdo ou caminho do modelo. */
public sealed interface Desfazer {
    record Edicao(String editId) implements Desfazer { }
    record Lixeira(String idLixeira) implements Desfazer { }
    record Arquivo(String id) implements Desfazer { }
    record Anotacao(List<String> ids) implements Desfazer {
        public Anotacao { ids = List.copyOf(ids); }
    }
    record Memoria(String id, long revisaoAnterior) implements Desfazer { }
    record Procedimento(String nome, long revisaoAnterior) implements Desfazer { }
    record Modelo(String anterior, String atual) implements Desfazer { }
    record Tarefa(String id) implements Desfazer { }

    default ObjectNode json(JsonMapper mapper) {
        ObjectNode no = mapper.createObjectNode();
        switch (this) {
            case Edicao d -> no.put("tipo", "edicao").put("editId", d.editId());
            case Lixeira d -> no.put("tipo", "lixeira").put("idLixeira", d.idLixeira());
            case Arquivo d -> no.put("tipo", "arquivo").put("id", d.id());
            case Anotacao d -> { no.put("tipo", "anotacao"); d.ids().forEach(no.putArray("ids")::add); }
            case Memoria d -> no.put("tipo", "memoria").put("id", d.id()).put("revisaoAnterior", d.revisaoAnterior());
            case Procedimento d -> no.put("tipo", "procedimento").put("nome", d.nome()).put("revisaoAnterior", d.revisaoAnterior());
            case Modelo d -> no.put("tipo", "modelo").put("anterior", d.anterior()).put("atual", d.atual());
            case Tarefa d -> no.put("tipo", "tarefa").put("id", d.id());
        }
        return no;
    }

    static Desfazer ler(JsonNode no) {
        if (no == null || !no.isObject()) return null;
        return switch (Json.texto(no.path("tipo"))) {
            case "edicao" -> new Edicao(Json.texto(no.path("editId")));
            case "lixeira" -> new Lixeira(Json.texto(no.path("idLixeira")));
            case "arquivo" -> new Arquivo(Json.texto(no.path("id")));
            case "anotacao" -> {
                var ids = new java.util.ArrayList<String>();
                for (JsonNode id : no.path("ids")) ids.add(Json.texto(id));
                yield new Anotacao(ids);
            }
            case "memoria" -> new Memoria(Json.texto(no.path("id")), Json.numero(no.path("revisaoAnterior")));
            case "procedimento" -> new Procedimento(Json.texto(no.path("nome")), Json.numero(no.path("revisaoAnterior")));
            case "modelo" -> new Modelo(Json.texto(no.path("anterior")), Json.texto(no.path("atual")));
            case "tarefa" -> new Tarefa(Json.texto(no.path("id")));
            default -> null;
        };
    }
}
