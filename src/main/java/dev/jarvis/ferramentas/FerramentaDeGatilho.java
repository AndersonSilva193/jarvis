package dev.jarvis.ferramentas;

import java.time.format.DateTimeFormatter;
import java.util.List;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.gatilhos.Gatilhos;
import dev.jarvis.gatilhos.RegraDeGatilho;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O que listar, pausar e apagar gatilho têm em comum: só na conversa autenticada (nunca numa tarefa
 * de fundo — uma tarefa nascida de gatilho não mexe nos gatilhos), a identidade vem do pedido e a
 * regra é achada pelo {@link Gatilhos#resolver}, sem o modelo inventar id.
 */
abstract class FerramentaDeGatilho implements Ferramenta {
    private static final DateTimeFormatter QUANDO = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    protected final Gatilhos gatilhos;
    protected final JsonMapper json;
    protected final JarvisProperties props;

    FerramentaDeGatilho(Gatilhos gatilhos, JsonMapper json, JarvisProperties props) {
        this.gatilhos = gatilhos;
        this.json = json;
        this.props = props;
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }

    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object");
        n.putObject("properties").putObject("gatilho").put("type", "string").put("maxLength", 200)
                .put("description", "Id do gatilho ou palavras dele; omita para 'esse gatilho'.");
        return n;
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        String recusa = recusa(contexto);
        if (recusa != null) return ResultadoFerramenta.texto(recusa);
        return ResultadoFerramenta.texto(responder(entrada, contexto.usuarioAutenticado()));
    }

    /** Nulo quando o pedido é do dono numa conversa; senão a frase da recusa. */
    static String recusa(OuvinteProgresso contexto) {
        if (!contexto.idTarefaDeFundo().isBlank() || "tarefa".equals(contexto.canal()))
            return "Indisponível dentro de uma tarefa de fundo.";
        if (contexto.usuarioAutenticado() <= 0
                || !("telegram".equals(contexto.canal()) || "hud".equals(contexto.canal())))
            return "Gatilho só se mexe numa mensagem direta sua. Nada foi feito.";
        return null;
    }

    protected abstract String responder(JsonNode entrada, long usuario);

    protected Gatilhos.Escolha escolher(JsonNode entrada, long usuario) {
        return gatilhos.resolver(usuario, entrada == null ? "" : Json.texto(entrada.path("gatilho")));
    }

    protected String semEscolha(Gatilhos.Escolha escolha, long usuario) {
        List<RegraDeGatilho> todas = gatilhos.listar(usuario);
        if (todas.isEmpty()) return "Não há gatilho nenhum.";
        var lista = escolha.candidatas().isEmpty() ? todas : escolha.candidatas();
        String cabeca = escolha.candidatas().isEmpty()
                ? "Nenhum gatilho casa com isso. Os que existem são:\n"
                : "Há mais de um gatilho; pergunte ao dono qual (nada foi feito):\n";
        return cabeca + String.join("\n", lista.stream().map(this::linha).toList());
    }

    protected String linha(RegraDeGatilho r) {
        String instrucao = r.instrucao().length() > 100 ? r.instrucao().substring(0, 100) + "…" : r.instrucao();
        return "[" + r.id() + "] quando " + r.evento() + ": " + instrucao
                + (r.ativa() ? "" : " (pausado)")
                + (r.ultimoDisparo() == null ? "" : " — disparou " + QUANDO.format(r.ultimoDisparo().atZone(props.zona())));
    }
}
