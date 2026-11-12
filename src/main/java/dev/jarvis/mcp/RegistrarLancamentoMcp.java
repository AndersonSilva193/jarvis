package dev.jarvis.mcp;

import java.io.IOException;

import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.Registrado;
import dev.jarvis.financas.Lancamento;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A base de {@code registrar_gasto} e {@code registrar_ganho}: o mesmo livro do Jarvis, com a origem
 * {@code escritorio} em cada lançamento e no diário — é por ela que o dono vê o que o agente lançou.
 *
 * <p>Duas ferramentas, e não uma com {@code tipo}, porque o nome já diz ao modelo do agente o que
 * ele está fazendo, e um gasto lançado como ganho é o erro que mais custa num resumo.
 */
abstract class RegistrarLancamentoMcp implements FerramentaMcp {

    private final FinancasService financas;
    private final JsonMapper json;
    private final Lancamento.Tipo tipo;

    RegistrarLancamentoMcp(FinancasService financas, JsonMapper json, Lancamento.Tipo tipo) {
        this.financas = financas;
        this.json = json;
        this.tipo = tipo;
    }

    @Override
    public ObjectNode esquema() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode propriedades = esquema.putObject("properties");
        propriedades.putObject("valor").put("type", "number").put("exclusiveMinimum", 0)
                .put("description", "Valor em reais, positivo, com ponto decimal (ex.: 49.90).");
        propriedades.putObject("categoria").put("type", "string").put("maxLength", 30)
                .put("description", "Categoria curta em minúsculas (ex.: combustível, alimentação, "
                        + "sites, hospedagem). Vazia vira 'outros'.");
        propriedades.putObject("descricao").put("type", "string").put("maxLength", 120)
                .put("description", "Detalhe curto opcional (ex.: posto da esquina).");
        propriedades.putObject("data").put("type", "string")
                .put("description", "Data no formato AAAA-MM-DD. Vazia é hoje. Não aceita data futura.");
        ArrayNode obrigatorios = esquema.putArray("required");
        obrigatorios.add("valor");
        obrigatorios.add("categoria");
        esquema.put("additionalProperties", false);
        return esquema;
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) throws IOException {
        JsonNode valor = argumentos.path("valor");
        if (!valor.isNumber()) {
            return ResultadoMcp.erro("valor tem de ser um número em reais, como 49.90.");
        }
        try {
            Registrado l = financas.registrarNoLivro(tipo, valor.doubleValue(),
                    Json.texto(argumentos.path("categoria")), Json.texto(argumentos.path("descricao")),
                    Json.texto(argumentos.path("data")), OrigemDaAcao.ESCRITORIO.chave());
            return ResultadoMcp.ok("Lancei. " + FinancasService.confirmacao(l));
        } catch (IllegalArgumentException e) {
            return ResultadoMcp.erro("Não lancei: " + e.getMessage());
        }
    }
}
