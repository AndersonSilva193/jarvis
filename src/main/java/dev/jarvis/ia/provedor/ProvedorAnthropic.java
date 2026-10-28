package dev.jarvis.ia.provedor;

import java.io.IOException;

import org.springframework.stereotype.Component;

import dev.jarvis.ia.ClaudeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * O {@link ProvedorDeModelo} da Anthropic: converte o pedido neutro, chama o {@link ClaudeClient} e
 * converte a resposta de volta.
 *
 * <p>É só tradução. Repetição em erro temporário, cache automático da conversa, TTL, esforço por
 * perfil e registro de custo continuam no {@code ClaudeClient}, que não mudou — e a escolha da
 * sobrecarga dele é feita pelas {@link OpcoesDoPedido}, uma por tipo de chamada:
 * <ul>
 *   <li>chamada curta (teto e prazo) → a de tentativa única, sem cache da conversa;</li>
 *   <li>esforço informado → a de perfil explícito, que é a do laço de ferramentas;</li>
 *   <li>o resto → a que deriva o perfil da origem.</li>
 * </ul>
 *
 * <p>O system e as ferramentas são renderizados uma vez por objeto neutro e devolvidos iguais nos
 * passos seguintes ({@link InstrucoesDeSistema#renderizada}): o mesmo prefixo, byte a byte e objeto
 * a objeto, em todas as idas e vindas do turno.
 */
@Component
public class ProvedorAnthropic implements ProvedorDeModelo {

    private final ClaudeClient claude;
    private final JsonMapper json;
    private final ConversorAnthropic conversor;

    public ProvedorAnthropic(ClaudeClient claude, JsonMapper json) {
        this.claude = claude;
        this.json = json;
        this.conversor = new ConversorAnthropic(json);
    }

    /**
     * O provedor sobre um cliente já existente, ou {@code null} sem cliente. Existe para os
     * construtores de compatibilidade dos chamadores — os que recebem o {@code ClaudeClient}, como
     * os testes montam — e o {@code null} preserva o "sem modelo" de quem já o aceitava.
     */
    public static ProvedorDeModelo sobre(ClaudeClient claude, JsonMapper json) {
        return claude == null ? null : new ProvedorAnthropic(claude, json);
    }

    @Override
    public RespostaDoModelo responder(PedidoAoModelo pedido) throws IOException, InterruptedException {
        ArrayNode system = pedido.sistema().renderizada(ConversorAnthropic.PROVEDOR, conversor::sistema);
        ArrayNode ferramentas = pedido.ferramentas().renderizada(ConversorAnthropic.PROVEDOR,
                conversor::ferramentas);
        ArrayNode mensagens = json.createArrayNode();
        for (Mensagem mensagem : pedido.mensagens()) {
            mensagens.add(conversor.mensagem(mensagem));
        }
        OpcoesDoPedido opcoes = pedido.opcoes();
        JsonNode raiz;
        if (opcoes.curta()) {
            raiz = claude.criarMensagem(opcoes.origem(), system, mensagens, ferramentas,
                    opcoes.maxTokens(), opcoes.prazo());
        } else if (opcoes.esforco() != null) {
            raiz = claude.criarMensagem(opcoes.origem(), opcoes.esforco(), system, mensagens,
                    ferramentas);
        } else {
            raiz = claude.criarMensagem(opcoes.origem(), system, mensagens, ferramentas);
        }
        return conversor.resposta(raiz);
    }
}
