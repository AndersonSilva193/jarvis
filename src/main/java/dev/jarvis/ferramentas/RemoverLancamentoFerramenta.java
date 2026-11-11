package dev.jarvis.ferramentas;

import java.util.Optional;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.Lancamento;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code remover_lancamento}: tira das contas um lançamento errado, pelo id.
 *
 * <p>Sem "Posso?", pela régua do {@code apagar_arquivo}: o lançamento não some do arquivo, só fica
 * marcado como removido, e a linha entra no diário. É também o caminho para corrigir o que um agente
 * do escritório lançou errado — o MCP não remove nada, de propósito.
 */
@Component
public class RemoverLancamentoFerramenta implements Ferramenta {

    private final FinancasService financas;
    private final JsonMapper json;

    public RemoverLancamentoFerramenta(FinancasService financas, JsonMapper json) {
        this.financas = financas;
        this.json = json;
    }

    @Override
    public String nome() {
        return "remover_lancamento";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.FINANCAS; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }

    @Override
    public String progresso() {
        return "corrigindo as finanças";
    }

    @Override
    public String descricao() {
        return "Tira das contas um lançamento financeiro errado, pelo id de 8 caracteres que o "
                + "resumo_financeiro e o registrar_lancamento mostram. Para corrigir um valor, remova e "
                + "lance de novo.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        esquema.putObject("properties").putObject("id").put("type", "string")
                .put("description", "Id do lançamento, como a1b2c3d4.");
        esquema.putArray("required").add("id");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        return remover(entrada, "");
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        return ResultadoFerramenta.texto(remover(entrada, progresso == null ? "" : progresso.canal()));
    }

    private String remover(JsonNode entrada, String canal) throws Exception {
        String id = Json.texto(entrada.path("id")).trim();
        if (id.isEmpty()) {
            return "Diga o id do lançamento; o resumo_financeiro mostra os últimos.";
        }
        Optional<Lancamento> removido = financas.remover(id, canal);
        return removido.map(l -> "Removido das contas: " + FinancasService.confirmacao(l))
                .orElse("Não achei lançamento ativo com o id " + id + ".");
    }
}
