package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.ProcedimentosService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tira de vista um procedimento aprendido ("esquece esse jeito de fazer proposta").
 *
 * <p>Nível "faz e avisa", pela régua do {@code apagar_arquivo}: o arquivo vai para
 * {@code .removidos} e não some, então não há o que perguntar. É o oposto do
 * {@code esquecer_memoria}, que pergunta porque fato pessoal esquecido não volta.
 */
@Component
public class EsquecerProcedimentoFerramenta implements Ferramenta {
    private final ProcedimentosService procedimentos;
    private final JsonMapper json;

    public EsquecerProcedimentoFerramenta(ProcedimentosService procedimentos, JsonMapper json) {
        this.procedimentos = procedimentos;
        this.json = json;
    }

    @Override public String nome() { return "esquecer_procedimento"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.MEMORIA; }
    @Override public boolean simples() { return true; }

    @Override public String descricao() {
        return "Tira um procedimento aprendido da biblioteca, pelo nome do índice. Use quando o "
                + "usuário disser que um procedimento está errado e não deve mais ser seguido, ou "
                + "pedir para você esquecê-lo. Não pergunta antes: o arquivo fica guardado à parte.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = json.createObjectNode().put("type", "object");
        s.putObject("properties").putObject("nome").put("type", "string");
        s.putArray("required").add("nome");
        return s;
    }

    @Override public String executar(JsonNode entrada) {
        return "Procedimentos exigem pedido autenticado.";
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        long usuario = LerProcedimentoFerramenta.usuario(progresso);
        if (usuario <= 0) return ResultadoFerramenta.texto(executar(entrada));
        String nome = Json.texto(entrada.path("nome")).trim();
        return procedimentos.esquecer(usuario, nome)
                .map(titulo -> ResultadoFerramenta.texto("Esqueci o procedimento: " + titulo + "."))
                .orElseGet(() -> ResultadoFerramenta.texto("Não há procedimento com o nome " + nome + "."));
    }
}
