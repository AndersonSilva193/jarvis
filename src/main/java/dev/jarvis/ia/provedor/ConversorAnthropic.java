package dev.jarvis.ia.provedor;

import java.util.ArrayList;
import java.util.List;

import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;
import dev.jarvis.ia.provedor.RespostaDoModelo.Uso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formato neutro ↔ Messages API da Anthropic, nos dois sentidos e sem perda.
 *
 * <p>"Sem perda" quer dizer o mesmo JSON de antes do formato neutro existir, chave por chave e na
 * mesma ordem: um byte diferente no corpo é prefixo diferente, e prefixo diferente é cache de prompt
 * perdido. Três regras seguram isso:
 * <ul>
 *   <li><b>Bloco que veio da API volta cru.</b> Texto e chamada guardam o bloco original
 *       ({@link Bloco.Texto#original()}), então {@code citations} e qualquer campo que a API venha a
 *       acrescentar atravessam intactos; o resto vira {@link Bloco.Opaco} e volta byte a byte — o
 *       {@code encrypted_content} da busca na web alterado é HTTP 400.</li>
 *   <li><b>Texto nascido em Java sai como string.</b> Mensagem de um texto só, sem original, vira
 *       {@code "content": "..."}, que é como o pedido do usuário e o turno compacto sempre foram.</li>
 *   <li><b>O system lido da API é devolvido como foi lido</b> ({@link #instrucoesDe}).</li>
 * </ul>
 */
public final class ConversorAnthropic {

    /** O nome que marca o que só a Anthropic entende. */
    public static final String PROVEDOR = "anthropic";

    private static final String BUSCA_DE_FERRAMENTAS = "tool_search_tool_bm25_20251119";

    private final JsonMapper json;

    public ConversorAnthropic(JsonMapper json) {
        this.json = json;
    }

    // ---- neutro → Anthropic ------------------------------------------------------------------

    public ObjectNode mensagem(Mensagem mensagem) {
        ObjectNode no = json.createObjectNode();
        no.put("role", mensagem.papel() == Mensagem.Papel.ASSISTENTE ? "assistant" : "user");
        List<Bloco> blocos = mensagem.blocos();
        if (blocos.size() == 1 && blocos.getFirst() instanceof Bloco.Texto t && t.original() == null) {
            no.put("content", t.texto());
            return no;
        }
        ArrayNode conteudo = json.createArrayNode();
        for (Bloco bloco : blocos) {
            JsonNode convertido = bloco(bloco);
            if (convertido != null) conteudo.add(convertido);
        }
        // Mensagem sem conteúdo a API recusa — e é o que sobra de uma que só tinha blocos de outro
        // provedor.
        if (conteudo.isEmpty()) {
            no.put("content", "(sem resposta)");
        } else {
            no.set("content", conteudo);
        }
        return no;
    }

    /** O bloco na forma da API, ou {@code null} para o opaco de outro provedor, que não vai. */
    public JsonNode bloco(Bloco bloco) {
        return switch (bloco) {
            case Bloco.Texto t -> nosso(t.original()) ? t.original().bruto()
                    : json.createObjectNode().put("type", "text").put("text", t.texto());
            case Bloco.Chamada c -> {
                if (nosso(c.original())) yield c.original().bruto();
                ObjectNode no = json.createObjectNode().put("type", "tool_use")
                        .put("id", c.id()).put("name", c.nome());
                no.set("input", c.entrada() == null || !c.entrada().isObject()
                        ? json.createObjectNode() : c.entrada());
                yield no;
            }
            case Bloco.Resultado r -> {
                ObjectNode no = json.createObjectNode().put("type", "tool_result")
                        .put("tool_use_id", r.id()).put("content", r.conteudo());
                if (r.erro()) no.put("is_error", true);
                yield no;
            }
            case Bloco.Opaco o -> o.de(PROVEDOR) ? o.bruto() : null;
        };
    }

    private static boolean nosso(Bloco.Opaco original) {
        return original != null && original.de(PROVEDOR) && original.bruto() != null;
    }

    /** Os blocos de texto do system, com o {@code cache_control} no que pede cache. */
    public ArrayNode sistema(InstrucoesDeSistema instrucoes) {
        ArrayNode blocos = json.createArrayNode();
        for (BlocoDeSistema bloco : instrucoes.blocos()) {
            ObjectNode no = blocos.addObject().put("type", "text").put("text", bloco.texto());
            if (bloco.cache() != null) {
                ObjectNode controle = no.putObject("cache_control").put("type", "ephemeral");
                // Só no 1h, como no PromptDeSistema: 5m é o padrão da API, e omitir o campo mantém
                // o corpo igual ao de antes do ANTHROPIC_CACHE_TTL existir.
                if ("1h".equals(bloco.cache())) controle.put("ttl", "1h");
            }
        }
        return blocos;
    }

    public ObjectNode definicao(DefinicaoDeFerramenta definicao) {
        ObjectNode no = json.createObjectNode();
        no.put("name", definicao.nome());
        no.put("description", definicao.descricao());
        no.set("input_schema", definicao.esquema());
        if (definicao.sobDemanda()) no.put("defer_loading", true);
        return no;
    }

    /**
     * As definições, depois a busca de ferramentas e depois as que a Anthropic executa — a ordem de
     * antes, porque a lista é renderizada antes do system e entra no mesmo prefixo de cache.
     */
    public ArrayNode ferramentas(ListaDeFerramentas lista) {
        ArrayNode definicoes = json.createArrayNode();
        for (DefinicaoDeFerramenta definicao : lista.definicoes()) {
            definicoes.add(definicao(definicao));
        }
        if (lista.buscaSobDemanda()) {
            definicoes.addObject().put("type", BUSCA_DE_FERRAMENTAS).put("name", "tool_search_tool_bm25");
        }
        for (Bloco.Opaco opaco : lista.doProvedor()) {
            if (opaco.de(PROVEDOR)) definicoes.add(opaco.bruto());
        }
        return definicoes;
    }

    // ---- Anthropic → neutro ------------------------------------------------------------------

    /** Uma mensagem {@code role}/{@code content}: a do histórico antigo em disco, ou de teste. */
    public Mensagem mensagemDe(JsonNode mensagem) {
        String papel = Json.texto(mensagem.path("role"));
        Mensagem.Papel neutro = switch (papel) {
            case "user" -> Mensagem.Papel.USUARIO;
            case "assistant" -> Mensagem.Papel.ASSISTENTE;
            default -> throw new IllegalArgumentException("papel desconhecido: " + papel);
        };
        JsonNode conteudo = mensagem.path("content");
        if (conteudo.isString()) {
            return Mensagem.texto(neutro, conteudo.stringValue());
        }
        return new Mensagem(neutro, blocosDe(conteudo));
    }

    /** O {@code content} da API em blocos neutros, guardando cada original. */
    public List<Bloco> blocosDe(JsonNode conteudo) {
        List<Bloco> blocos = new ArrayList<>();
        if (conteudo == null || !conteudo.isArray()) return blocos;
        for (JsonNode bloco : conteudo) {
            Bloco.Opaco original = Bloco.Opaco.de(PROVEDOR, bloco);
            String tipo = Json.texto(bloco.path("type"));
            switch (tipo) {
                case "text" -> blocos.add(new Bloco.Texto(Json.texto(bloco.path("text")), original));
                case "tool_use" -> blocos.add(new Bloco.Chamada(Json.texto(bloco.path("id")),
                        Json.texto(bloco.path("name")), bloco.path("input"), original));
                // Resultado com imagem ou blocos dentro é do computer use e do histórico antigo:
                // nesse caso fica na caixa fechada, inteiro.
                case "tool_result" -> blocos.add(bloco.path("content").isString()
                        ? new Bloco.Resultado(Json.texto(bloco.path("tool_use_id")),
                                bloco.path("content").stringValue(),
                                bloco.path("is_error").asBoolean(false))
                        : original);
                // Assinado e preso ao prefixo exato que o produziu: vale só neste turno.
                case "thinking", "redacted_thinking" ->
                        blocos.add(new Bloco.Opaco(PROVEDOR, bloco, true, ""));
                case "server_tool_use" -> blocos.add("web_search".equals(Json.texto(bloco.path("name")))
                        ? new Bloco.Opaco(PROVEDOR, bloco, false,
                                Json.texto(bloco.path("input").path("query")))
                        : original);
                default -> blocos.add(original);
            }
        }
        return blocos;
    }

    /**
     * O system em blocos de texto com {@code cache_control}, que é a forma em que o
     * {@code SistemaDoJarvis} o entrega. A forma lida fica registrada: devolvida à Anthropic, ela
     * volta como o mesmo objeto, e os outros provedores leem o texto e a validade do cache.
     */
    public static InstrucoesDeSistema instrucoesDe(ArrayNode blocos) {
        if (blocos == null) return new InstrucoesDeSistema(List.of());
        List<BlocoDeSistema> neutros = new ArrayList<>();
        for (JsonNode bloco : blocos) {
            JsonNode controle = bloco.path("cache_control");
            String cache = controle.isObject()
                    ? (Json.texto(controle.path("ttl")).isBlank() ? "5m" : Json.texto(controle.path("ttl")))
                    : null;
            neutros.add(new BlocoDeSistema(Json.texto(bloco.path("text")), cache));
        }
        return new InstrucoesDeSistema(neutros).comRenderizada(PROVEDOR, blocos);
    }

    public RespostaDoModelo resposta(JsonNode raiz) {
        JsonNode usage = raiz.path("usage");
        Uso uso = new Uso(Json.numero(usage.path("input_tokens")),
                Json.numero(usage.path("output_tokens")),
                Json.numero(usage.path("cache_creation_input_tokens")),
                Json.numero(usage.path("cache_read_input_tokens")),
                (int) Json.numero(usage.path("server_tool_use").path("web_search_requests")));
        return new RespostaDoModelo(blocosDe(raiz.path("content")),
                motivoDe(Json.texto(raiz.path("stop_reason"))), uso);
    }

    static MotivoDeParada motivoDe(String motivo) {
        return switch (motivo) {
            case "end_turn", "stop_sequence" -> MotivoDeParada.FIM;
            case "tool_use" -> MotivoDeParada.FERRAMENTA;
            case "pause_turn" -> MotivoDeParada.PAUSA;
            case "max_tokens", "model_context_window_exceeded" -> MotivoDeParada.LIMITE_DE_TOKENS;
            case "refusal" -> MotivoDeParada.RECUSA;
            default -> MotivoDeParada.OUTRO;
        };
    }
}
