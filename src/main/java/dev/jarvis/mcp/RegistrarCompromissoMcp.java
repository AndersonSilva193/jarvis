package dev.jarvis.mcp;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code registrar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class RegistrarCompromissoMcp implements FerramentaMcp {

    private final AgendaService agenda;
    private final JsonMapper json;

    public RegistrarCompromissoMcp(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "registrar_compromisso";
    }

    @Override
    public String descricao() {
        return "Marca um compromisso na agenda do dono (agenda.csv na pasta do Jarvis no PC). Confira antes com agenda_de_hoje para não marcar em cima de outro. Devolve o id. 'Gravo quando o PC voltar' é sucesso: o compromisso está na fila.";
    }

    @Override
    public ObjectNode esquema() {
        return PedidosDaAgenda.esquemaRegistrar(json);
    }

    @Override
    public ResultadoMcp executar(JsonNode argumentos) {
        PedidosDaAgenda.Saida saida = PedidosDaAgenda.registrar(agenda, argumentos);
        if (saida.erro()) return ResultadoMcp.erro(saida.texto());
        return ResultadoMcp.ok(saida.texto(), json.createObjectNode().put("id", saida.id()).put("noPc", saida.noPc()));
    }
}
