package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.Registrado;
import dev.jarvis.financas.Lancamento;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code registrar_lancamento}: "gastei 50 de gasolina", "recebi 800 do site da barbearia".
 *
 * <p>O livro é o mesmo do agente financeiro do escritório (MCP), no {@link FinancasService}. Não
 * pergunta antes: o lançamento é reversível pelo {@code remover_lancamento} e entra no diário.
 */
@Component
public class RegistrarLancamentoFerramenta implements Ferramenta {

    private final FinancasService financas;
    private final JsonMapper json;

    public RegistrarLancamentoFerramenta(FinancasService financas, JsonMapper json) {
        this.financas = financas;
        this.json = json;
    }

    @Override
    public String nome() {
        return "registrar_lancamento";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.FINANCAS; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }

    @Override
    public String progresso() {
        return "anotando nas finanças";
    }

    @Override
    public String descricao() {
        return "Anota um gasto ou um ganho do dono no livro financeiro (o mesmo que o agente "
                + "financeiro do escritório usa). Para 'gastei 50 de gasolina', 'paguei a hospedagem', "
                + "'recebi 800 do site'. Valor em reais com ponto decimal; data AAAA-MM-DD só quando "
                + "não for hoje. Devolve o id, que serve para remover_lancamento.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode propriedades = esquema.putObject("properties");
        ArrayNode tipos = propriedades.putObject("tipo").put("type", "string").putArray("enum");
        tipos.add("gasto");
        tipos.add("ganho");
        propriedades.putObject("valor").put("type", "number")
                .put("description", "Valor em reais, positivo (ex.: 49.90).");
        propriedades.putObject("categoria").put("type", "string")
                .put("description", "Categoria curta em minúsculas (combustível, alimentação, sites...).");
        propriedades.putObject("descricao").put("type", "string")
                .put("description", "Detalhe curto opcional.");
        propriedades.putObject("data").put("type", "string")
                .put("description", "AAAA-MM-DD; omita para hoje.");
        ArrayNode obrigatorios = esquema.putArray("required");
        obrigatorios.add("tipo");
        obrigatorios.add("valor");
        obrigatorios.add("categoria");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        return lancar(entrada, "");
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        return ResultadoFerramenta.texto(lancar(entrada, progresso == null ? "" : progresso.canal()));
    }

    private String lancar(JsonNode entrada, String canal) throws Exception {
        Lancamento.Tipo tipo = Lancamento.Tipo.porChave(Json.texto(entrada.path("tipo")));
        if (tipo == null) {
            return "Não anotei: diga se é gasto ou ganho.";
        }
        JsonNode valor = entrada.path("valor");
        if (!valor.isNumber()) {
            return "Não anotei: o valor tem de ser um número, como 49.90.";
        }
        try {
            Registrado l = financas.registrarNoLivro(tipo, valor.doubleValue(),
                    Json.texto(entrada.path("categoria")), Json.texto(entrada.path("descricao")),
                    Json.texto(entrada.path("data")), canal);
            return "Anotado. " + FinancasService.confirmacao(l);
        } catch (IllegalArgumentException e) {
            // Erro de entrada não é falha da ação: o modelo lê e corrige.
            return "Não anotei: " + e.getMessage();
        }
    }
}
