package dev.jarvis.ferramentas;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Uma "habilidade" do Jarvis. Para criar uma nova, basta fazer uma classe @Component que implemente
 * esta interface: o Spring a encontra sozinho e ela passa a ser oferecida ao modelo.
 *
 * Regra de ouro: cada ferramenta faz UMA coisa específica e limitada. Nada de "executar comando qualquer".
 */
public interface Ferramenta {

    /** Nome que o modelo usa para chamar (letras minúsculas, números e _). */
    String nome();

    /** Explica ao modelo o que a ferramenta faz e quando usar. Quanto mais claro, melhor ele escolhe. */
    String descricao();

    /** JSON Schema dos parâmetros de entrada. */
    ObjectNode esquemaEntrada();

    /** Executa e devolve um texto com o resultado, que volta para o modelo. */
    String executar(JsonNode entrada) throws Exception;
}
