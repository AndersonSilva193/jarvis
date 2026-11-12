package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.escritorio.AgendaService;
import dev.jarvis.escritorio.PedidosDaAgenda;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** {@code registrar_compromisso}: escreve no agenda.csv da pasta do Jarvis no PC. */
@Component
public class RegistrarCompromissoFerramenta implements Ferramenta {

    private final AgendaService agenda;
    private final JsonMapper json;

    public RegistrarCompromissoFerramenta(AgendaService agenda, JsonMapper json) {
        this.agenda = agenda;
        this.json = json;
    }

    @Override
    public String nome() {
        return "registrar_compromisso";
    }

    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.INFORMACAO; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }
    @Override public boolean oferecida() { return agenda.configurado(); }

    @Override
    public String progresso() {
        return "marcando na agenda";
    }

    @Override
    public String descricao() {
        return "Marca um compromisso na agenda do dono (agenda.csv na pasta do Jarvis no PC). Para 'marca reunião amanhã às 14h'. Não é lembrete: para avisar numa hora, use criar_lembrete.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        return PedidosDaAgenda.esquemaRegistrar(json);
    }

    @Override
    public String executar(JsonNode entrada) {
        return PedidosDaAgenda.registrar(agenda, entrada).texto();
    }
}
