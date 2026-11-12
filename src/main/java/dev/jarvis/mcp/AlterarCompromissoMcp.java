package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code alterar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class AlterarCompromissoMcp implements FerramentaMcp {

    private final AgendaService agenda;
    private final JsonMapper json;

    public AlterarCompromissoMcp(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "alterar_compromisso";
    }

    @Override
    public String descricao() {
        return "Muda um compromisso da agenda pelo id que a agenda_de_hoje mostra; mande só os campos que mudam.";
    }

    @Override
    public ObjectNode esquema() {
        return PedidosDaAgenda.esquemaAlterar(json);
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) {
        PedidosDaAgenda.Saida saida = PedidosDaAgenda.alterar(agenda, argumentos);
        if (saida.erro()) return ResultadoMcp.erro(saida.texto());
        return ResultadoMcp.ok(saida.texto(), json.createObjectNode().put("id", saida.id()).put("noPc", saida.noPc()));
    }
}
