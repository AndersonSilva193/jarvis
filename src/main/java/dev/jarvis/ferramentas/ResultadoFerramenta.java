package dev.jarvis.ferramentas;

import java.nio.file.Path;
import java.util.List;
import dev.jarvis.conversa.Confirmacao;

/** Metadados emitidos pelo código da ferramenta, nunca extraídos de caminhos escritos pelo modelo. */
public record ResultadoFerramenta(String texto, List<Artefato> artefatos,
        List<Referencia> referencias, Confirmacao confirmacao) {
    public record Artefato(Path arquivo, String nome, String mime) { }
    public record Referencia(String nome, String url) { }
    public ResultadoFerramenta {
        artefatos = List.copyOf(artefatos);
        referencias = List.copyOf(referencias);
    }
    public static ResultadoFerramenta texto(String texto) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null);
    }
}
