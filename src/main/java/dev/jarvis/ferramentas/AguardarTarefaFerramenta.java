package dev.jarvis.ferramentas;

import java.time.Instant;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tarefas.*;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AguardarTarefaFerramenta implements Ferramenta {
    private final EsperasDaTarefa esperas;
    private final JsonMapper json;
    public AguardarTarefaFerramenta(EsperasDaTarefa esperas, JsonMapper json) { this.esperas = esperas; this.json = json; }
    @Override public String nome() { return "aguardar_tarefa"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Somente em tarefa de fundo: suspende sem gastar passos nem tempo ativo até pc disponível, operacao_pc "
                + "concluída (alvo=ID real), site configurado disponível (alvo=nome) ou horario (ate=ISO-8601 com fuso). "
                + "Use em vez de consultar status repetidamente. Não aceita URL livre.";
    }
    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object"); var p = n.putObject("properties");
        p.putObject("condicao").put("type", "string").putArray("enum").add("pc").add("operacao_pc").add("site").add("horario");
        p.putObject("alvo").put("type", "string"); p.putObject("ate").put("type", "string");
        n.putArray("required").add("condicao"); return n;
    }
    @Override public String executar(JsonNode entrada) { return "Disponível somente em tarefa de fundo."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (ContextoDaTarefa.atual() == null || contexto.idTarefaDeFundo().isBlank())
            return ResultadoFerramenta.texto("Disponível somente em tarefa de fundo.");
        var tipo = SuspensaoDaTarefa.Tipo.valueOf(Json.texto(entrada.path("condicao")).toUpperCase(java.util.Locale.ROOT));
        String ate = Json.texto(entrada.path("ate"));
        throw esperas.criar(tipo, Json.texto(entrada.path("alvo")), ate.isBlank() ? null : Instant.parse(ate));
    }
}
