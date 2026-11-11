package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.financas.FinancasService;
import dev.jarvis.financas.Lancamento;
import tools.jackson.databind.json.JsonMapper;

/** {@code registrar_ganho}: um ganho do dono no livro do Jarvis. */
@Component
public class RegistrarGanhoMcp extends RegistrarLancamentoMcp {

    public RegistrarGanhoMcp(FinancasService financas, JsonMapper json) {
        super(financas, json, Lancamento.Tipo.GANHO);
    }

    @Override
    public String nome() {
        return "registrar_ganho";
    }

    @Override
    public String descricao() {
        return "Lança um GANHO do dono (dinheiro que entrou, como um site vendido) no livro financeiro "
                + "do Jarvis, o mesmo que ele consulta pelo Telegram. Use só para ganho que o dono "
                + "informou ou confirmou. Devolve o id do lançamento.";
    }
}
