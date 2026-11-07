package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tarefas.ContextoDaTarefa;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O único jeito de uma tarefa de fundo terminar {@code CONCLUIDA}. Quem decide se ela pode ser
 * chamada é o {@code CicloDeFerramentas}, que viu a sequência inteira: depois de uma ferramenta que
 * {@link Ferramenta#mudaEstado() muda estado}, ele recusa a conclusão até uma leitura conferir o
 * resultado. Aqui só se valida a entrada e se monta, em Java, a frase do aviso final — com o
 * {@code como_conferi} no fim ("Organizei 14 arquivos em 4 pastas. Conferi listando a pasta de novo.").
 *
 * <p>Só existe no canal tarefa e só roda com contexto interno. Encerra o turno sem outra chamada ao
 * modelo: o texto devolvido é o resultado da tarefa.
 */
@Component
public class ConcluirTarefaFerramenta implements Ferramenta {
    static final int MAX_RESULTADO = 1_000;
    static final int MAX_CONFERENCIA = 300;
    private final JsonMapper json;

    public ConcluirTarefaFerramenta(JsonMapper json) { this.json = json; }

    @Override public String nome() { return "concluir_tarefa"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return true; }
    @Override public String progresso() { return "concluindo a tarefa"; }
    @Override public String descricao() {
        return "Encerra esta tarefa de fundo como concluída. Chame só no fim, com o resultado em uma ou duas "
                + "frases e, se a tarefa mudou algo, em como_conferi como você conferiu (por exemplo "
                + "\"listando a pasta de novo\"). Depois de qualquer mudança, confira com uma ferramenta de "
                + "leitura antes: sem conferência o servidor recusa. Se não conseguiu, não chame: explique o motivo.";
    }
    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object"); var p = n.putObject("properties");
        p.putObject("resultado").put("type", "string").put("maxLength", MAX_RESULTADO)
                .put("description", "O que foi feito, começando pelo resultado.");
        p.putObject("como_conferi").put("type", "string").put("maxLength", MAX_CONFERENCIA)
                .put("description", "Como o resultado foi conferido, ou que não havia como conferir.");
        n.putArray("required").add("resultado"); return n;
    }
    @Override public String executar(JsonNode entrada) { return "Disponível somente em tarefa de fundo."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (ContextoDaTarefa.atual() == null || contexto.idTarefaDeFundo().isBlank())
            return ResultadoFerramenta.texto("Só existe em tarefa de fundo; responda com o resultado.");
        String resultado = limpar(Json.texto(entrada.path("resultado")));
        String como = limpar(Json.texto(entrada.path("como_conferi")));
        if (resultado.isEmpty()) return ResultadoFerramenta.texto("resultado vazio; diga o que foi feito.");
        if (resultado.length() > MAX_RESULTADO)
            return ResultadoFerramenta.texto("resultado acima de " + MAX_RESULTADO + " caracteres; resuma.");
        if (como.length() > MAX_CONFERENCIA)
            return ResultadoFerramenta.texto("como_conferi acima de " + MAX_CONFERENCIA + " caracteres; resuma.");
        return ResultadoFerramenta.imediata(frase(resultado, como), null);
    }

    /** O resultado, mais "Conferi ..." quando houver — sem repetir o verbo se o modelo já o escreveu. */
    static String frase(String resultado, String como) {
        if (como.isEmpty()) return resultado;
        String texto = pontuar(resultado);
        String conferencia = como.replaceAll("[.!\\s]+$", "");
        if (conferencia.toLowerCase(java.util.Locale.ROOT).startsWith("conferi"))
            conferencia = Character.toUpperCase(conferencia.charAt(0)) + conferencia.substring(1);
        else conferencia = "Conferi " + minuscula(conferencia);
        return texto + " " + conferencia + ".";
    }

    private static String limpar(String texto) {
        return texto == null ? "" : texto.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }
    private static String pontuar(String texto) {
        return texto.matches(".*[.!?…]$") ? texto : texto + ".";
    }
    /** "Listando a pasta" vira "listando a pasta"; sigla ("PC", "XLSX") fica como veio. */
    private static String minuscula(String texto) {
        if (texto.length() > 1 && Character.isUpperCase(texto.charAt(0)) && Character.isLowerCase(texto.charAt(1)))
            return Character.toLowerCase(texto.charAt(0)) + texto.substring(1);
        return texto;
    }
}
