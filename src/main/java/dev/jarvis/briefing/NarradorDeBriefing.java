package dev.jarvis.briefing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * Lê o briefing em voz de gente: pega o texto já montado em Java e pede ao Claude que o apresente
 * com a personalidade, no canal {@link Canal#BRIEFING} (fala para voz, sem markdown nem link).
 *
 * <p>Fala com o {@link ClaudeClient} direto, e não pelo Assistente, por dois motivos. Primeiro,
 * dependência: o Assistente puxa as Ferramentas, e entre elas está a do briefing — o ciclo
 * BriefingService → Assistente → Ferramentas → BriefingFerramenta → BriefingService nem sobe.
 * Segundo, risco: mandar a lista de ferramentas aqui abriria a porta para o modelo chamar
 * {@code briefing_do_dia} no meio da narração do próprio briefing. Sem ferramenta não há laço.
 *
 * <p>Os números nunca passam pelo modelo para serem recalculados: eles chegam prontos no texto e a
 * instrução é só de apresentação. Se a chamada falhar, quem pediu fica com o texto determinístico.
 */
@Component
public class NarradorDeBriefing {

    private static final Logger log = LoggerFactory.getLogger(NarradorDeBriefing.class);

    private final ClaudeClient claude;
    private final SistemaDoJarvis sistema;
    private final JsonMapper json;

    public NarradorDeBriefing(ClaudeClient claude, SistemaDoJarvis sistema, JsonMapper json) {
        this.claude = claude;
        this.sistema = sistema;
        this.json = json;
    }

    /**
     * @return o briefing narrado, ou string vazia se a chamada falhar — nunca uma exceção, porque
     *         briefing sem enfeite é melhor que manhã sem briefing.
     */
    public String narrar(String textoDoBriefing) {
        try {
            ArrayNode mensagens = json.createArrayNode();
            mensagens.addObject()
                    .put("role", "user")
                    .put("content", instrucao(textoDoBriefing));

            JsonNode resposta = claude.criarMensagem(sistema.blocos(Canal.BRIEFING), mensagens,
                    json.createArrayNode());
            return extrairTexto(resposta.path("content"));
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("Não consegui narrar o briefing ({}); fica o texto montado em Java.", e.getMessage());
            return "";
        }
    }

    private static String instrucao(String textoDoBriefing) {
        return """
                Dê o bom dia e apresente o briefing abaixo com as suas palavras, na ordem em que ele
                veio. Os dados já estão prontos e conferidos: não altere nenhum número, nome ou
                horário, não acrescente nada que não esteja aqui e não comente o que faltou.

                %s""".formatted(textoDoBriefing);
    }

    private static String extrairTexto(JsonNode conteudo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conteudo.size(); i++) {
            JsonNode bloco = conteudo.get(i);
            if ("text".equals(Json.texto(bloco.path("type")))) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(Json.texto(bloco.path("text")));
            }
        }
        return sb.toString().trim();
    }
}
