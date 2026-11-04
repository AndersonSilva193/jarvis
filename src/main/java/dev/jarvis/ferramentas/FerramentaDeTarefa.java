package dev.jarvis.ferramentas;

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tarefas.ResolvedorDeTarefa;
import dev.jarvis.tarefas.TarefaDeFundo;
import dev.jarvis.tarefas.TarefaFalada;
import dev.jarvis.tarefas.TarefasDeFundo;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que as quatro ferramentas de acompanhamento têm em comum: só na conversa autenticada, a
 * identidade vem do pedido (nunca da entrada) e a tarefa é achada pelo {@link ResolvedorDeTarefa}.
 * O provider evita o ciclo Ferramentas → ferramenta → executor → Ferramentas.
 */
abstract class FerramentaDeTarefa implements Ferramenta {
    protected final ObjectProvider<TarefasDeFundo> tarefas;
    protected final JsonMapper json;
    protected final JarvisProperties props;

    FerramentaDeTarefa(ObjectProvider<TarefasDeFundo> tarefas, JsonMapper json, JarvisProperties props) {
        this.tarefas = tarefas; this.json = json; this.props = props;
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object");
        n.putObject("properties").putObject("tarefa").put("type", "string").put("maxLength", 200)
                .put("description", "Id da tarefa ou palavras do objetivo; omita se o dono não disse qual.");
        return n;
    }

    @Override public final ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!contexto.idTarefaDeFundo().isBlank())
            return ResultadoFerramenta.texto("Indisponível dentro de uma tarefa de fundo.");
        if (contexto.usuarioAutenticado() <= 0) return ResultadoFerramenta.texto("Pedido autenticado necessário.");
        String referencia = entrada == null ? "" : Json.texto(entrada.path("tarefa"));
        return ResultadoFerramenta.texto(responder(referencia, contexto.usuarioAutenticado()));
    }

    protected abstract String responder(String referencia, long usuario);

    /** Ativas primeiro, mais novas no topo; as encerradas só das últimas 24 h. */
    protected List<TarefaDeFundo.Retrato> ativas(long usuario) {
        return tarefas.getObject().listar(usuario).stream().filter(TarefaFalada::ativa)
                .sorted(java.util.Comparator.comparing(TarefaDeFundo.Retrato::criadaEm).reversed()).toList();
    }
    protected List<TarefaDeFundo.Retrato> encerradasRecentes(long usuario) {
        var limite = tarefas.getObject().agora().minus(Duration.ofHours(24));
        return tarefas.getObject().listar(usuario).stream().filter(t -> !TarefaFalada.ativa(t))
                .filter(t -> !(t.concluidaEm() == null ? t.atualizadaEm() : t.concluidaEm()).isBefore(limite))
                .sorted(java.util.Comparator.comparing(TarefaDeFundo.Retrato::atualizadaEm).reversed()).toList();
    }

    /** A escolha entre as ativas, ou a frase que diz por que não houve uma. */
    protected ResolvedorDeTarefa.Escolha escolher(String referencia, long usuario) {
        return ResolvedorDeTarefa.resolver(referencia, ativas(usuario));
    }
    protected String semEscolha(ResolvedorDeTarefa.Escolha escolha, String referencia, long usuario) {
        var ativas = ativas(usuario);
        if (ativas.isEmpty()) return "Nenhuma tarefa de fundo ativa agora.";
        var lista = escolha.candidatas().isEmpty() ? ativas : escolha.candidatas();
        String cabeca = escolha.candidatas().isEmpty() && !referencia.isBlank()
                ? "Nenhuma tarefa ativa casa com \"" + referencia.strip() + "\". As ativas são:\n"
                : "Há mais de uma tarefa ativa; pergunte ao dono qual (nada foi feito):\n";
        return cabeca + String.join("\n", lista.stream().map(this::linha).toList());
    }
    protected String linha(TarefaDeFundo.Retrato t) {
        return TarefaFalada.linha(t, tarefas.getObject().agora(), props.zona());
    }
}
