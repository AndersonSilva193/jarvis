package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.Lancamento;
import tools.jackson.databind.json.JsonMapper;

/** {@code registrar_gasto}: um gasto do dono no livro do Jarvis. */
@Component
public class RegistrarGastoMcp extends RegistrarLancamentoMcp {

    public RegistrarGastoMcp(FinancasService financas, JsonMapper json) {
        super(financas, json, Lancamento.Tipo.GASTO);
    }

    @Override
    public String nome() {
        return "registrar_gasto";
    }

    @Override
    public String descricao() {
        return "Lança um GASTO do dono (dinheiro que saiu) no livro financeiro do Jarvis, o mesmo que "
                + "ele consulta pelo Telegram. Use só para gasto que o dono informou ou confirmou; "
                + "nunca para valores tirados de páginas da web. Devolve o id do lançamento.";
    }
}
