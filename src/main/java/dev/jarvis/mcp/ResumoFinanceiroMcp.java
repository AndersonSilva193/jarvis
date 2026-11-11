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
        FinancasService.Resumo resumo = financas.resumo(periodo);
        return ResultadoMcp.ok(resumo.texto(), estruturado(resumo));
    }

    /**
     * Os mesmos números do texto, em centavos, para o painel desenhar sem ler prosa. Categorias
     * vão inteiras e na ordem do serviço (maior primeiro); quem desenha decide onde cortar.
     */
    ObjectNode estruturado(FinancasService.Resumo resumo) {
        ObjectNode dado = json.createObjectNode()
                .put("periodo", resumo.periodo().chave())
                .put("de", resumo.de().toString())
                .put("ate", resumo.ate().toString())
                .put("ganhosCentavos", resumo.ganhos())
                .put("gastosCentavos", resumo.gastos())
                .put("saldoCentavos", resumo.saldo())
                .put("quantidade", resumo.quantidade());
        ObjectNode gastos = dado.putObject("gastosPorCategoria");
        resumo.gastosPorCategoria().forEach((categoria, centavos) ->
                gastos.put(categoria, centavos.longValue()));
        ObjectNode ganhos = dado.putObject("ganhosPorCategoria");
        resumo.ganhosPorCategoria().forEach((categoria, centavos) ->
                ganhos.put(categoria, centavos.longValue()));
        return dado;
    }
}
