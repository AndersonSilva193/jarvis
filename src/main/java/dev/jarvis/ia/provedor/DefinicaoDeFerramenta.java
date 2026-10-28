package dev.jarvis.ia.provedor;

import tools.jackson.databind.JsonNode;

/**
 * Uma ferramenta do Jarvis como o modelo a vê.
 *
 * @param sobDemanda a definição só é carregada quando o modelo a procura. Quem implementa a
 *                   procura é o provedor; quem não implementa manda a definição inteira
 */
public record DefinicaoDeFerramenta(String nome, String descricao, JsonNode esquema,
        boolean sobDemanda) { }
