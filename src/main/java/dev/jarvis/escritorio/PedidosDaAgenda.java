package dev.jarvis.escritorio;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Os três pedidos de escrita na agenda — marcar, alterar, cancelar — como chegam da conversa e do
 * MCP: o mesmo esquema, a mesma conferência e o mesmo texto de volta, para as duas portas não
 * divergirem.
 */
public final class PedidosDaAgenda {

    /** O que volta a quem pediu. {@code erro} é argumento a corrigir ou compromisso que não existe. */
    public record Saida(String texto, boolean erro, String id, boolean noPc) { }

    private PedidosDaAgenda() {
    }

    public static Saida registrar(AgendaService agenda, JsonNode args) {
        try {
            String titulo = Json.texto(args.path("compromisso"));
            String data = Json.texto(args.path("data"));
            String inicio = Json.texto(args.path("inicio"));
            String fim = Json.texto(args.path("fim"));
            AgendaService.Gravacao g = agenda.registrar(data, inicio, fim, titulo, Json.texto(args.path("local")));
            String quando = AgendaService.limpar(data) + (inicio.isBlank() ? "" : " " + AgendaService.hora(inicio));
            return saida("Marquei: " + AgendaService.limpar(titulo) + ", " + quando + " (id " + g.id() + ").", g);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Saida("Não marquei: " + e.getMessage(), true, "", false);
        }
    }

    public static Saida alterar(AgendaService agenda, JsonNode args) {
        Map<String, String> campos = new LinkedHashMap<>();
        for (String campo : new String[] {"data", "inicio", "fim", "compromisso", "local"})
            if (args.path(campo).isString()) campos.put(campo, args.path(campo).stringValue());
        try {
            AgendaService.Gravacao g = agenda.alterar(Json.texto(args.path("id")), campos);
            return saida("Alterei o compromisso " + g.id() + ".", g);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Saida("Não alterei: " + e.getMessage(), true, "", false);
        }
    }

    public static Saida cancelar(AgendaService agenda, JsonNode args) {
        try {
            AgendaService.Gravacao g = agenda.cancelar(Json.texto(args.path("id")));
            return saida("Cancelei o compromisso " + g.id() + ".", g);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new Saida("Não cancelei: " + e.getMessage(), true, "", false);
        }
    }

    private static Saida saida(String feito, AgendaService.Gravacao g) {
        return switch (g.estado()) {
            case GRAVADO -> new Saida(feito, false, g.id(), true);
            case ID_INEXISTENTE -> new Saida("Não achei o compromisso " + g.id()
                    + " na agenda do PC; confira o id com agenda_de_hoje.", true, g.id(), false);
            case ARQUIVO_TRAVADO -> new Saida(feito + " A agenda está aberta no PC; gravo no agenda.csv assim "
                    + "que ela for fechada.", false, g.id(), false);
            case PC_OFFLINE -> new Saida(feito + " Ainda não está no agenda.csv: gravo quando o PC voltar.", false,
                    g.id(), false);
            case AGENTE_ANTIGO -> new Saida(feito + " Ainda não está no agenda.csv: o agente do PC precisa ser "
                    + "atualizado, e gravo assim que for.", false, g.id(), false);
            case RECUSADO -> new Saida("Não gravei: o PC recusou escrever no agenda.csv (confira o arquivo na "
                    + "pasta do Jarvis).", true, g.id(), false);
            case FALHOU -> new Saida(feito + " O PC ainda não confirmou o agenda.csv; fica na fila e tento de novo "
                    + "em alguns minutos.", false, g.id(), false);
        };
    }

    // ---------------------------------------------------------------- esquemas

    public static ObjectNode esquemaRegistrar(JsonMapper json) {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode props = esquema.putObject("properties");
        props.putObject("data").put("type", "string").put("description", "Dia, dd/MM/aaaa ou AAAA-MM-DD.");
        props.putObject("inicio").put("type", "string").put("description", "Hora de início, como 14:00. Vazio é o dia inteiro.");
        props.putObject("fim").put("type", "string").put("description", "Hora de fim, opcional.");
        props.putObject("compromisso").put("type", "string").put("maxLength", AgendaService.MAX_TITULO)
                .put("description", "O que é, curto (ex.: Reunião com a barbearia).");
        props.putObject("local").put("type", "string").put("maxLength", AgendaService.MAX_LOCAL)
                .put("description", "Onde, opcional.");
        esquema.putArray("required").add("data").add("compromisso");
        esquema.put("additionalProperties", false);
        return esquema;
    }

    public static ObjectNode esquemaAlterar(JsonMapper json) {
        ObjectNode esquema = esquemaRegistrar(json);
        ((ObjectNode) esquema.path("properties")).putObject("id").put("type", "string")
                .put("description", "O id que a agenda_de_hoje mostra, como a1b2c3d4.");
        esquema.putArray("required").removeAll().add("id");
        return esquema;
    }

    public static ObjectNode esquemaCancelar(JsonMapper json) {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        esquema.putObject("properties").putObject("id").put("type", "string")
                .put("description", "O id que a agenda_de_hoje mostra, como a1b2c3d4.");
        esquema.putArray("required").add("id");
        esquema.put("additionalProperties", false);
        return esquema;
    }
}
