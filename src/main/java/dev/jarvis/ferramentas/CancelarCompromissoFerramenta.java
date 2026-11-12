package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code cancelar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class CancelarCompromissoFerramenta implements Ferramenta {

    private final AgendaService agenda;
    private final JsonMapper json;

    public CancelarCompromissoFerramenta(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "cancelar_compromisso";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.INFORMACAO; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }
    @Override public boolean oferecida() { return agenda.configurado(); }

    @Override
    public String progresso() {
        return "tirando da agenda";
    }

    @Override
    public String descricao() {
        return "Tira um compromisso da agenda pelo id que a agenda_de_hoje mostra.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        return PedidosDaAgenda.esquemaCancelar(json);
    }

    @Override
    public String executar(JsonNode entrada) {
        return PedidosDaAgenda.cancelar(agenda, entrada).texto();
    }
}
