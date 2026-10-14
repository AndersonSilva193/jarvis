package dev.jarvis.ferramentas;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reúne todas as ferramentas (o Spring injeta a lista) e faz a ponte com o formato da API. */
@Component
public class Ferramentas {

    private static final Logger log = LoggerFactory.getLogger(Ferramentas.class);

    private final Map<String, Ferramenta> porNome = new LinkedHashMap<>();
    private final JsonMapper json;

    public Ferramentas(List<Ferramenta> ferramentas, JsonMapper json) {
        this.json = json;
        for (Ferramenta f : ferramentas) {
            porNome.put(f.nome(), f);
        }
        log.info("Ferramentas disponíveis: {}", porNome.keySet());
    }

    /** Lista no formato "tools" da Messages API. */
    public ArrayNode definicoes() {
        ArrayNode lista = json.createArrayNode();
        for (Ferramenta f : porNome.values()) {
            ObjectNode def = lista.addObject();
            def.put("name", f.nome());
            def.put("description", f.descricao());
            def.set("input_schema", f.esquemaEntrada());
        }
        return lista;
    }

    /** Recebe um bloco "tool_use" do modelo e devolve o bloco "tool_result" correspondente. */
    public ObjectNode executar(JsonNode blocoToolUse) {
        return executar(blocoToolUse, new dev.jarvis.conversa.OuvinteProgresso() { }, saida -> { });
    }

    public ObjectNode executar(JsonNode blocoToolUse, dev.jarvis.conversa.OuvinteProgresso progresso,
            java.util.function.Consumer<ResultadoFerramenta> receber) {
        String nome = Json.texto(blocoToolUse.path("name"));
        JsonNode entrada = blocoToolUse.path("input");

        ObjectNode resultado = json.createObjectNode();
        resultado.put("type", "tool_result");
        resultado.put("tool_use_id", Json.texto(blocoToolUse.path("id")));

        Ferramenta ferramenta = porNome.get(nome);
        if (ferramenta == null) {
            resultado.put("content", "Ferramenta desconhecida: " + nome);
            resultado.put("is_error", true);
            return resultado;
        }

        log.info("Executando ferramenta {}", nome);
        dev.jarvis.conversa.OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, ferramenta.progresso(), null, null));
        try {
            ResultadoFerramenta detalhado = ferramenta.executarDetalhado(entrada, progresso);
            receber.accept(detalhado);
            String saida = detalhado.texto();
            resultado.put("content", saida == null || saida.isBlank() ? "(sem resultado)" : saida);
        } catch (Exception e) {
            log.warn("Ferramenta {} falhou: {}", nome, e.getMessage());
            resultado.put("content", "Erro ao executar " + nome + ": " + e.getMessage());
            resultado.put("is_error", true);
        }
        return resultado;
    }
}
