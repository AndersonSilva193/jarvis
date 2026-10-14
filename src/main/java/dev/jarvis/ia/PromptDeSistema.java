package dev.jarvis.ia;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Monta o campo {@code system} da Messages API em <b>dois blocos</b>, nesta ordem:
 *
 * <ol>
 *   <li><b>fixo</b> — o texto da personalidade, byte a byte como está no arquivo. É ele que leva o
 *       {@code cache_control}, então nada variável pode entrar aqui: um caractere diferente e o
 *       cache de prompt é perdido (a API casa o prefixo exato).</li>
 *   <li><b>dinâmico</b> — data, canal e regras. Vem depois e nunca é cacheado, justamente porque
 *       muda a cada pedido.</li>
 * </ol>
 *
 * Classe sem Spring e com método estático de propósito: assim dá para testar o que vai para a API
 * sem subir contexto nem fingir chamada HTTP.
 */
final class PromptDeSistema {

    private static final DateTimeFormatter FORMATO_DATA =
            DateTimeFormatter.ofPattern("EEEE, dd/MM/yyyy HH:mm", Locale.of("pt", "BR"));

    private PromptDeSistema() {
    }

    /**
     * @param personalidade texto do arquivo de personalidade, usado sem nenhuma substituição
     * @param cachear       se o bloco fixo é grande o bastante para o modelo aceitar cachear
     */
    static ArrayNode montar(JsonMapper json, String personalidade, boolean cachear,
            Canal canal, String nomeUsuario, ZonedDateTime agora) {
        ArrayNode blocos = json.createArrayNode();

        ObjectNode fixo = blocos.addObject();
        fixo.put("type", "text");
        fixo.put("text", personalidade);
        if (cachear) {
            fixo.putObject("cache_control").put("type", "ephemeral");
        }

        ObjectNode dinamico = blocos.addObject();
        dinamico.put("type", "text");
        dinamico.put("text", parteDinamica(canal, nomeUsuario, agora));
        return blocos;
    }

    private static String parteDinamica(Canal canal, String nomeUsuario, ZonedDateTime agora) {
        return """
                Agora são %s (fuso %s). Use isso para interpretar "hoje", "amanhã", "às 15h" etc.
                Você está atendendo %s pelo canal: %s.

                Regras técnicas:
                - Use as ferramentas sempre que precisar de dados reais e nunca invente o resultado de
                  uma ferramenta.
                - Se não existir ferramenta para o que foi pedido, diga com clareza que ainda não
                  consegue fazer isso.

                Formato para este canal:
                %s
                """.formatted(agora.format(FORMATO_DATA), agora.getZone(), nomeUsuario, canal.nome(),
                canal.regrasDeFormato());
    }
}
