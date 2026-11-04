package dev.jarvis.ferramentas;

import java.util.ArrayList;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.Canal;
import dev.jarvis.tarefas.TarefaDeFundo;
import dev.jarvis.tarefas.TarefasDeFundo;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** O provider evita o ciclo Ferramentas → ferramenta de início → executor → Ferramentas. */
@Component
public class IniciarTarefaDeFundoFerramenta implements Ferramenta {
    private final ObjectProvider<TarefasDeFundo> tarefas;
    private final JsonMapper json;
    public IniciarTarefaDeFundoFerramenta(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json) {
        this.tarefas = tarefas; this.json = json;
    }
    @Override public String nome() { return "iniciar_tarefa_de_fundo"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Inicia em segundo plano pedidos com muitos passos, que levam minutos ou precisam esperar PC, site ou horário. "
                + "A conversa fica livre e o servidor avisa ao terminar. Passe objetivo completo, plano curto e ações sensíveis "
                + "previstas (chave do catálogo e alvo exato da rotina); lista vazia se não houver. O servidor monta uma única "
                + "aprovação se necessário. Delegue antes de executar os passos; não use para pedidos simples.";
    }
    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object"); var p = n.putObject("properties");
        p.putObject("objetivo").put("type", "string").put("maxLength", 4000);
        var plano = p.putObject("plano").put("type", "array").put("minItems", 1).put("maxItems", 8);
        plano.putObject("items").put("type", "string").put("maxLength", 300);
        var acoes = p.putObject("acoes_previstas").put("type", "array").put("maxItems", 20);
        var item = acoes.putObject("items").put("type", "object"); var campos = item.putObject("properties");
        var chaves = campos.putObject("chave").put("type", "string").putArray("enum");
        for (var a : dev.jarvis.autonomia.AcaoAutonoma.values()) chaves.add(a.chave());
        campos.putObject("alvo").put("type", "string"); item.putArray("required").add("chave");
        n.putArray("required").add("objetivo").add("plano").add("acoes_previstas"); return n;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!contexto.idTarefaDeFundo().isBlank() || contexto.usuarioAutenticado() <= 0
                || contexto.idPedido() == null || contexto.idPedido().isBlank()
                || !(contexto.canal().equals("telegram") || contexto.canal().equals("hud")))
            return ResultadoFerramenta.imediata("Início exige pedido autenticado de conversa.", null);
        try {
            var plano = new ArrayList<String>(); entrada.path("plano").forEach(p -> plano.add(p.asText()));
            var acoes = new ArrayList<TarefaDeFundo.AcaoPrevista>();
            entrada.path("acoes_previstas").forEach(a -> acoes.add(new TarefaDeFundo.AcaoPrevista(
                    Json.texto(a.path("chave")), Json.texto(a.path("alvo")))));
            return tarefas.getObject().iniciar(Json.texto(entrada.path("objetivo")), plano, acoes,
                    contexto.usuarioAutenticado(), Canal.valueOf(contexto.canal().toUpperCase(java.util.Locale.ROOT)),
                    contexto.chatDeOrigem(), TarefaDeFundo.Origem.PEDIDO);
        } catch (IllegalArgumentException e) { return ResultadoFerramenta.imediata(e.getMessage(), null); }
    }
}
