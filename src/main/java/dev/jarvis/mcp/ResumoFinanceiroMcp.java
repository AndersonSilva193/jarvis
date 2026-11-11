package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.PeriodoFinanceiro;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** {@code resumo_financeiro}: totais, categorias e últimos lançamentos de um período. */
@Component
public class ResumoFinanceiroMcp implements FerramentaMcp {

    private final FinancasService financas;
    private final JsonMapper json;

    public ResumoFinanceiroMcp(FinancasService financas, JsonMapper json) {
        this.financas = financas;
        this.json = json;
    }

    @Override
    public String nome() {
        return "resumo_financeiro";
    }

    @Override
    public String descricao() {
        return "Resumo financeiro do dono num período: ganhos, gastos, saldo, gastos e ganhos por "
                + "categoria e os últimos lançamentos com id. Os valores já vêm somados pelo Jarvis; "
                + "use-os como estão.";
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode periodo = esquema.putObject("properties").putObject("periodo").put("type", "string")
                .put("description", "Período do resumo. Vazio é o mês atual.");
        ArrayNode valores = periodo.putArray("enum");
        for (String chave : PeriodoFinanceiro.chaves()) {
            valores.add(chave);
        }
        esquema.put("additionalProperties", false);
        return esquema;
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) {
        PeriodoFinanceiro periodo = PeriodoFinanceiro.porChave(Json.texto(argumentos.path("periodo")));
        if (periodo == null) {
            return ResultadoMcp.erro("periodo tem de ser um de "
                    + String.join(", ", PeriodoFinanceiro.chaves()) + ".");
        }
        return ResultadoMcp.ok(financas.resumo(periodo).texto());
    }
}
