package dev.jarvis.ia.provedor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;
import dev.jarvis.ia.provedor.RespostaDoModelo.Uso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formato neutro ↔ {@code /api/chat} do Ollama, que é o que a ação {@code inferir} do agente lê.
 *
 * <p>Ao contrário do {@link ConversorAnthropic}, aqui a conversão <b>perde</b> de propósito:
 * <ul>
 *   <li><b>Nada da Anthropic sai.</b> {@link Bloco.Opaco} não vai, nem o {@code original} de texto e
 *       chamada — só o {@code texto()} e a {@code entrada()}. Pensamento assinado, busca na web e
 *       busca de ferramentas são do provedor que os produziu, e o agente recusaria campo que não
 *       conhece de qualquer jeito.</li>
 *   <li><b>O system vira a primeira mensagem</b>, com os blocos juntos por linha em branco. O
 *       {@code cache} de cada bloco não tem par no Ollama e é ignorado.</li>
 *   <li><b>O resultado de ferramenta vira uma mensagem {@code tool}</b>, com o {@code tool_name} da
 *       chamada de mesmo id. O Ollama não tem {@code is_error}, então o erro vai escrito no
 *       começo do conteúdo.</li>
 *   <li>{@link ListaDeFerramentas#doProvedor()} (a busca na web, que a Anthropic executa) não vai;
 *       definição {@code sobDemanda} vai inteira, que é a regra de quem não implementa a procura.</li>
 * </ul>
 */
final class ConversorOllama {

    /** O nome com que este provedor guarda a forma dele no {@link InstrucoesDeSistema}. */
    static final String PROVEDOR = "local";

    /** O que o agente aceita em nome de ferramenta; outro nome derruba o pedido inteiro lá. */
    static final Pattern NOME_DE_FERRAMENTA = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JsonMapper json;

    ConversorOllama(JsonMapper json) {
        this.json = json;
    }

    // ---- neutro → Ollama ---------------------------------------------------------------------

    /** O {@code dados} do comando {@code inferir}: mensagens, ferramentas e o {@code think}. */
    ObjectNode pedido(PedidoAoModelo pedido, boolean pensar) {
        ObjectNode corpo = json.createObjectNode();
        ArrayNode mensagens = corpo.putArray("messages");
        String sistema = pedido.sistema().renderizada(PROVEDOR, ConversorOllama::sistema);
        if (!sistema.isBlank()) {
            mensagens.addObject().put("role", "system").put("content", sistema);
        }
        Map<String, String> nomesPorId = new HashMap<>();
        for (Mensagem mensagem : pedido.mensagens()) {
            acrescentar(mensagens, mensagem, nomesPorId);
        }
        ArrayNode ferramentas = pedido.ferramentas().renderizada(PROVEDOR, this::ferramentas);
        if (!ferramentas.isEmpty()) {
            corpo.set("tools", ferramentas);
        }
        corpo.put("think", pensar);
        return corpo;
    }

    static String sistema(InstrucoesDeSistema instrucoes) {
        StringBuilder sb = new StringBuilder();
        for (BlocoDeSistema bloco : instrucoes.blocos()) {
            if (bloco.texto().isBlank()) continue;
            if (!sb.isEmpty()) sb.append("\n\n");
            sb.append(bloco.texto());
        }
        return sb.toString();
    }

    ArrayNode ferramentas(ListaDeFerramentas lista) {
        ArrayNode saida = json.createArrayNode();
        for (DefinicaoDeFerramenta definicao : lista.definicoes()) {
            ObjectNode funcao = saida.addObject().put("type", "function").putObject("function")
                    .put("name", definicao.nome()).put("description", definicao.descricao());
            funcao.set("parameters", definicao.esquema() != null && definicao.esquema().isObject()
                    ? definicao.esquema() : json.createObjectNode().put("type", "object"));
        }
        return saida;
    }

    private void acrescentar(ArrayNode saida, Mensagem mensagem, Map<String, String> nomesPorId) {
        StringBuilder texto = new StringBuilder();
        if (mensagem.papel() == Mensagem.Papel.ASSISTENTE) {
            ArrayNode chamadas = json.createArrayNode();
            for (Bloco bloco : mensagem.blocos()) {
                if (bloco instanceof Bloco.Texto t) {
                    juntar(texto, t.texto());
                } else if (bloco instanceof Bloco.Chamada c) {
                    nomesPorId.put(c.id(), c.nome());
                    chamadas.add(chamada(c));
                }
            }
            // Só opaco (um pensamento, uma busca na web) não sobra nada a dizer ao Ollama.
            if (texto.isEmpty() && chamadas.isEmpty()) return;
            ObjectNode no = saida.addObject().put("role", "assistant").put("content", texto.toString());
            if (!chamadas.isEmpty()) no.set("tool_calls", chamadas);
            return;
        }
        // Os resultados primeiro: no Ollama eles seguem a mensagem do assistente que chamou.
        for (Bloco bloco : mensagem.blocos()) {
            if (bloco instanceof Bloco.Resultado r) {
                saida.add(resultado(nomesPorId.get(r.id()),
                        r.erro() ? "Erro: " + r.conteudo() : r.conteudo()));
            } else if (bloco instanceof Bloco.Texto t) {
                juntar(texto, t.texto());
            }
        }
        if (!texto.isEmpty()) {
            saida.addObject().put("role", "user").put("content", texto.toString());
        }
    }

    private ObjectNode chamada(Bloco.Chamada chamada) {
        ObjectNode no = json.createObjectNode().put("type", "function");
        no.putObject("function").put("name", chamada.nome())
                .set("arguments", chamada.entrada() != null && chamada.entrada().isObject()
                        ? chamada.entrada() : json.createObjectNode());
        return no;
    }

    /** A mensagem {@code tool}; o nome só vai quando é um que o agente aceita. */
    ObjectNode resultado(String nome, String conteudo) {
        ObjectNode no = json.createObjectNode().put("role", "tool");
        if (nome != null && NOME_DE_FERRAMENTA.matcher(nome).matches()) no.put("tool_name", nome);
        no.put("content", conteudo);
        return no;
    }

    /** A resposta do local de volta como mensagem {@code assistant}, para a volta de correção. */
    ObjectNode assistente(RespostaDoModelo resposta) {
        StringBuilder texto = new StringBuilder();
        ArrayNode chamadas = json.createArrayNode();
        for (Bloco bloco : resposta.blocos()) {
            if (bloco instanceof Bloco.Texto t) juntar(texto, t.texto());
            else if (bloco instanceof Bloco.Chamada c) chamadas.add(chamada(c));
        }
        ObjectNode no = json.createObjectNode().put("role", "assistant").put("content", texto.toString());
        if (!chamadas.isEmpty()) no.set("tool_calls", chamadas);
        return no;
    }

    private static void juntar(StringBuilder sb, String texto) {
        if (texto == null || texto.isEmpty()) return;
        if (!sb.isEmpty()) sb.append("\n\n");
        sb.append(texto);
    }

    // ---- Ollama → neutro ---------------------------------------------------------------------

    /**
     * O {@code dados} do resultado do {@code inferir}, que o agente remonta do Ollama: a
     * {@code message} e as métricas. A chamada sai <b>como veio</b> — com o argumento cru, mesmo em
     * texto ou ausente —, porque quem confere o formato é o {@code ProvedorLocal}, contra o esquema.
     * O {@code thinking} fica de fora: não tem assinatura e não volta a lugar nenhum.
     */
    RespostaDoModelo resposta(JsonNode dados) {
        JsonNode mensagem = dados.path("message");
        List<Bloco> blocos = new ArrayList<>();
        String conteudo = Json.texto(mensagem.path("content"));
        if (!conteudo.isBlank()) {
            blocos.add(new Bloco.Texto(conteudo));
        }
        // O Ollama não dá id às chamadas; o sufixo sorteado evita colisão entre passos do turno.
        String prefixo = "local_" + UUID.randomUUID().toString().substring(0, 8) + "_";
        int chamadas = 0;
        for (JsonNode chamada : mensagem.path("tool_calls")) {
            JsonNode funcao = chamada.path("function");
            blocos.add(new Bloco.Chamada(prefixo + chamadas++, Json.texto(funcao.path("name")),
                    funcao.get("arguments")));
        }
        MotivoDeParada motivo = chamadas > 0 ? MotivoDeParada.FERRAMENTA
                : switch (Json.texto(dados.path("done_reason"))) {
                    case "stop" -> MotivoDeParada.FIM;
                    case "length" -> MotivoDeParada.LIMITE_DE_TOKENS;
                    default -> MotivoDeParada.OUTRO;
                };
        Uso uso = new Uso(Json.numero(dados.path("prompt_eval_count")),
                Json.numero(dados.path("eval_count")), 0, 0, 0);
        return new RespostaDoModelo(blocos, motivo, uso);
    }
}
