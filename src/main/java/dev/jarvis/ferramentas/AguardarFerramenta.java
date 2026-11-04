package dev.jarvis.ferramentas;

import java.time.Duration;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tarefas.ContextoDaTarefa;
import dev.jarvis.tarefas.EsperasDaTarefa;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Testa de novo em 2 minutos": suspende a tarefa por um tempo relativo. É agendamento, não sleep —
 * a suspensão libera o trabalhador e quem acorda a tarefa é a varredura do executor, pelo relógio.
 * Só existe no canal tarefa (o registro não a oferece à conversa) e só roda com contexto interno.
 */
@Component
public class AguardarFerramenta implements Ferramenta {
    static final int MAX_MINUTOS = 60;
    private final EsperasDaTarefa esperas;
    private final JsonMapper json;
    public AguardarFerramenta(EsperasDaTarefa esperas, JsonMapper json) { this.esperas = esperas; this.json = json; }
    @Override public String nome() { return "aguardar"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Suspende esta tarefa por 1 a 60 minutos sem gastar passos nem tempo ativo, e continua daqui depois. "
                + "Use para \"testa de novo em 2 minutos\". Para horário absoluto, PC, operação ou site use aguardar_tarefa.";
    }
    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object"); var p = n.putObject("properties");
        p.putObject("minutos").put("type", "integer").put("minimum", 1).put("maximum", MAX_MINUTOS);
        p.putObject("motivo").put("type", "string").put("maxLength", 120);
        n.putArray("required").add("minutos").add("motivo"); return n;
    }
    @Override public String executar(JsonNode entrada) { return "Disponível somente em tarefa de fundo."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (ContextoDaTarefa.atual() == null || contexto.idTarefaDeFundo().isBlank())
            return ResultadoFerramenta.texto("Disponível somente em tarefa de fundo.");
        JsonNode valor = entrada.path("minutos");
        int minutos = valor.isNumber() ? valor.asInt() : valor.isString() && valor.stringValue().strip().matches("\\d{1,3}")
                ? Integer.parseInt(valor.stringValue().strip()) : 0;
        if (minutos < 1 || minutos > MAX_MINUTOS)
            return ResultadoFerramenta.texto("minutos precisa ser um inteiro de 1 a " + MAX_MINUTOS + ".");
        // O motivo vai à listagem do dono, não ao prompt de outra pessoa; mesmo assim, uma linha curta.
        String motivo = Json.texto(entrada.path("motivo")).replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
        if (motivo.length() > 120) motivo = motivo.substring(0, 120);
        throw esperas.aguardar(Duration.ofMinutes(minutos), motivo);
    }
}
