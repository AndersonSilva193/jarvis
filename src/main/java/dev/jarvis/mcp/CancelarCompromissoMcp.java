package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code cancelar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class CancelarCompromissoMcp implements FerramentaMcp {

    private final AgendaService agenda;
    private final JsonMapper json;

    public CancelarCompromissoMcp(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "cancelar_compromisso";
    }

    @Override
    public String descricao() {
        return "Tira um compromisso da agenda pelo id que a agenda_de_hoje mostra. Só com pedido ou confirmação do dono.";
    }

    @Override
    public ObjectNode esquema() {
        return PedidosDaAgenda.esquemaCancelar(json);
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) {
        PedidosDaAgenda.Saida saida = PedidosDaAgenda.cancelar(agenda, argumentos);
        if (saida.erro()) return ResultadoMcp.erro(saida.texto());
        return ResultadoMcp.ok(saida.texto(), json.createObjectNode().put("id", saida.id()).put("noPc", saida.noPc()));
    }
}
