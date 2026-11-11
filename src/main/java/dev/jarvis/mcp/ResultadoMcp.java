package dev.jarvis.mcp;

import tools.jackson.databind.JsonNode;

/**
 * O que uma {@link FerramentaMcp} devolve: o texto, se foi erro ({@code isError} no MCP) e, quando
 * houver, a mesma informação em JSON ({@code structuredContent}).
 *
 * <p>O texto continua sendo o que o modelo do agente lê. O estruturado existe para quem desenha
 * (o painel de operação do Hermes3D): ler número de prosa seria farejar texto, e erraria calado no
 * dia em que a frase mudasse.
 */
public record ResultadoMcp(String texto, boolean erro, JsonNode estruturado) {

    public ResultadoMcp(String texto, boolean erro) {
        this(texto, erro, null);
    }

    public static ResultadoMcp ok(String texto) {
        return new ResultadoMcp(texto, false, null);
    }

    public static ResultadoMcp ok(String texto, JsonNode estruturado) {
        return new ResultadoMcp(texto, false, estruturado);
    }

    public static ResultadoMcp erro(String texto) {
        return new ResultadoMcp(texto, true, null);
    }
}
