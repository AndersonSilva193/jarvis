package dev.jarvis.mcp;

/**
 * O que uma {@link FerramentaMcp} devolve: o texto e se foi erro ({@code isError} no MCP).
 */
public record ResultadoMcp(String texto, boolean erro) {

    public static ResultadoMcp ok(String texto) {
        return new ResultadoMcp(texto, false);
    }

    public static ResultadoMcp erro(String texto) {
        return new ResultadoMcp(texto, true);
    }
}
