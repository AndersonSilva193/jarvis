package dev.jarvis.ia.provedor;

import java.util.ArrayList;
import java.util.List;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A {@link Mensagem} em JSON, que é como o histórico vai ao disco — legível por qualquer provedor.
 *
 * <pre>
 * {"papel":"usuario","blocos":[{"tipo":"texto","texto":"oi"}]}
 * {"tipo":"chamada_ferramenta","id":"...","nome":"...","entrada":{...}}
 * {"tipo":"resultado_ferramenta","id":"...","conteudo":"...","erro":false}
 * {"tipo":"opaco_do_provedor","provedor":"anthropic","bruto":{...}}
 * </pre>
 *
 * Um provedor que não é o do original lê {@code texto}, {@code nome}, {@code entrada} e
 * {@code conteudo}, e deixa o opaco de lado. O turno compacto, que é a maior parte do arquivo, é só
 * {@code texto}.
 *
 * <p>Lê também o formato de antes, {@code role}/{@code content} da Anthropic: arquivo gravado por
 * uma versão anterior volta convertido, e é gravado neutro no fim do turno seguinte.
 */
public final class FormatoNeutro {

    private final JsonMapper json;
    private final ConversorAnthropic legado;

    public FormatoNeutro(JsonMapper json) {
        this.json = json;
        this.legado = new ConversorAnthropic(json);
    }

    public ObjectNode paraJson(Mensagem mensagem) {
        ObjectNode no = json.createObjectNode();
        no.put("papel", mensagem.papel().chave());
        var blocos = no.putArray("blocos");
        for (Bloco bloco : mensagem.blocos()) {
            ObjectNode b = blocos.addObject();
            switch (bloco) {
                case Bloco.Texto t -> {
                    b.put("tipo", "texto").put("texto", t.texto());
                    if (t.original() != null) b.set("original", opaco(t.original()));
                }
                case Bloco.Chamada c -> {
                    b.put("tipo", "chamada_ferramenta").put("id", c.id()).put("nome", c.nome());
                    b.set("entrada", c.entrada() == null ? json.createObjectNode() : c.entrada());
                    if (c.original() != null) b.set("original", opaco(c.original()));
                }
                case Bloco.Resultado r -> b.put("tipo", "resultado_ferramenta").put("id", r.id())
                        .put("conteudo", r.conteudo()).put("erro", r.erro());
                case Bloco.Opaco o -> {
                    b.put("tipo", "opaco_do_provedor");
                    b.setAll(opaco(o));
                }
            }
        }
        return no;
    }

    private ObjectNode opaco(Bloco.Opaco opaco) {
        ObjectNode no = json.createObjectNode().put("provedor", opaco.provedor());
        no.set("bruto", opaco.bruto());
        if (opaco.soNoTurno()) no.put("soNoTurno", true);
        if (!opaco.consultaWeb().isBlank()) no.put("consultaWeb", opaco.consultaWeb());
        return no;
    }

    /** A mensagem salva, no formato neutro ou no antigo. Formato que não é nenhum dos dois estoura. */
    public Mensagem deJson(JsonNode no) {
        if (!no.has("papel")) {
            return legado.mensagemDe(no);
        }
        Mensagem.Papel papel = Mensagem.Papel.porChave(Json.texto(no.path("papel")));
        if (papel == null) {
            throw new IllegalArgumentException("papel desconhecido: " + Json.texto(no.path("papel")));
        }
        List<Bloco> blocos = new ArrayList<>();
        for (JsonNode b : no.path("blocos")) {
            Bloco bloco = switch (Json.texto(b.path("tipo"))) {
                case "texto" -> new Bloco.Texto(Json.texto(b.path("texto")), opacoDe(b.path("original")));
                case "chamada_ferramenta" -> new Bloco.Chamada(Json.texto(b.path("id")),
                        Json.texto(b.path("nome")), b.path("entrada"), opacoDe(b.path("original")));
                case "resultado_ferramenta" -> new Bloco.Resultado(Json.texto(b.path("id")),
                        Json.texto(b.path("conteudo")), b.path("erro").asBoolean(false));
                case "opaco_do_provedor" -> opacoDe(b);
                default -> null;
            };
            if (bloco != null) blocos.add(bloco);
        }
        // Mensagem sem bloco nenhum não vai a provedor nenhum: sobra a marcação de sempre.
        return blocos.isEmpty() ? Mensagem.texto(papel, "(sem resposta)") : new Mensagem(papel, blocos);
    }

    private static Bloco.Opaco opacoDe(JsonNode no) {
        if (!no.isObject() || !no.has("bruto")) return null;
        return new Bloco.Opaco(Json.texto(no.path("provedor")), no.path("bruto"),
                no.path("soNoTurno").asBoolean(false), Json.texto(no.path("consultaWeb")));
    }
}
