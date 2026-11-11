package dev.jarvis.mcp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Uma ferramenta que o Jarvis oferece aos agentes do escritório pelo servidor MCP.
 *
 * <p><b>Não é uma {@code Ferramenta}</b>, e é de propósito: as ferramentas da conversa são do dono,
 * e algumas mexem no PC, apagam arquivos ou passam pelo {@code Aprovacoes}. Os agentes do escritório
 * leem a internet, e uma instrução escondida numa página não pode chegar a nada disso. Então o
 * catálogo daqui é separado, pequeno e fechado: o Spring injeta só as implementações desta interface
 * no {@link ServidorMcp}, e nenhuma delas aparece na lista do modelo do Jarvis.
 *
 * <p>Ferramenta nova é uma classe {@code @Component} neste pacote. Antes de criar, a pergunta é a do
 * {@code verificar_site}: o que o agente escolhe é um valor de uma lista ou um número, e nunca um
 * destino, um comando ou um texto que o Jarvis repita ao dono.
 */
public interface FerramentaMcp {

    /** O nome que o agente chama. Minúsculas e {@code _}, como as ferramentas da conversa. */
    String nome();

    /** O que o modelo do agente lê para escolher. Curta e com o formato dos argumentos. */
    String descricao();

    /** O JSON Schema dos argumentos ({@code inputSchema} no MCP). */
    ObjectNode esquema();

    /**
     * Executa com os argumentos que o agente mandou (objeto, nunca nulo).
     *
     * <p>Argumento errado volta como {@link ResultadoMcp#erro(String)} com a frase do que corrigir:
     * no MCP, erro de ferramenta vai no resultado ({@code isError}), para o modelo poder se corrigir.
     */
    ResultadoMcp executar(JsonNode argumentos) throws Exception;
}
