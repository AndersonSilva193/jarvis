package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code alterar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class AlterarCompromissoFerramenta implements Ferramenta {

    private final AgendaService agenda;
    private final JsonMapper json;

    public AlterarCompromissoFerramenta(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "alterar_compromisso";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.INFORMACAO; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }
    @Override public boolean oferecida() { return agenda.configurado(); }

    @Override
    public String progresso() {
        return "mudando a agenda";
    }

    @Override
    public String descricao() {
        return "Muda um compromisso da agenda pelo id que a agenda_de_hoje mostra; mande só os campos que mudam.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        return PedidosDaAgenda.esquemaAlterar(json);
    }

    @Override
    public String executar(JsonNode entrada) {
        return PedidosDaAgenda.alterar(agenda, entrada).texto();
    }
}
