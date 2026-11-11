package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.PeriodoFinanceiro;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code resumo_financeiro}: "quanto gastei este mês?". As contas são feitas em Java, em centavos —
 * o modelo só lê o resultado, pelo motivo do {@code grafico_de_dados}: número errado com cara de
 * certo é o pior erro que uma conta pode ter.
 */
@Component
public class ResumoFinanceiroFerramenta implements Ferramenta {

    private final FinancasService financas;
    private final JsonMapper json;

    public ResumoFinanceiroFerramenta(FinancasService financas, JsonMapper json) {
        this.financas = financas;
        this.json = json;
    }

    @Override
    public String nome() {
        return "resumo_financeiro";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.FINANCAS; }
    @Override public boolean simples() { return true; }

    @Override
    public String progresso() {
        return "somando as finanças";
    }

    @Override
    public String descricao() {
        return "Resumo das finanças do dono num período: ganhos, gastos, saldo, por categoria e os "
                + "últimos lançamentos com id (inclui o que o agente financeiro do escritório lançou). "
                + "Os totais já vêm somados; repita-os sem refazer a conta.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode periodo = esquema.putObject("properties").putObject("periodo").put("type", "string")
                .put("description", "Omita para o mês atual.");
        ArrayNode valores = periodo.putArray("enum");
        for (String chave : PeriodoFinanceiro.chaves()) {
            valores.add(chave);
        }
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        PeriodoFinanceiro periodo = PeriodoFinanceiro.porChave(Json.texto(entrada.path("periodo")));
        if (periodo == null) {
            return "Período desconhecido; use um de " + String.join(", ", PeriodoFinanceiro.chaves()) + ".";
        }
        return financas.resumo(periodo).texto();
    }
}
