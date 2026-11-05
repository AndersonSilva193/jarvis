package dev.jarvis.gatilhos;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

import dev.jarvis.lembretes.Recorrencia;
import dev.jarvis.lembretes.Repeticao;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;

/**
 * O recorte de uma fonte que a regra escuta: "o site barbearia", "arquivo xlsx", "toda segunda às
 * 8h". Todos os campos são opcionais menos o {@code quando} do {@link FonteDeGatilho#HORARIO}; nulo é
 * "qualquer um".
 *
 * <p>No horário a recorrência é a {@link Repeticao} dos lembretes, com o dia do mês tirado do
 * {@code quando} — a mesma regra do {@code criar_lembrete}: é a data que o dono pediu, não um campo
 * à parte.
 */
public record Filtro(String site, String nome, String formato, String tipo,
        LocalDateTime quando, Repeticao repeticao) {

    public static final Filtro VAZIO = new Filtro(null, null, null, null, null, Repeticao.NENHUMA);
    public static final String[] DIAS = {
            "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo" };
    public static final Set<String> TIPOS_DA_COTA = Set.of("antecipado", "reserva");
    public static final Set<String> FORMATOS = Set.of("txt", "md", "json", "docx", "pdf", "xlsx", "csv");
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");

    public Filtro {
        site = vazioViraNulo(site);
        nome = vazioViraNulo(nome);
        formato = vazioViraNulo(formato);
        tipo = vazioViraNulo(tipo);
        repeticao = repeticao == null ? Repeticao.NENHUMA : repeticao;
    }

    /**
     * Lê o filtro que o modelo mandou, conferindo contra a fonte. Campo que a fonte não entende é
     * recusado em vez de ignorado: uma regra "PC ligou, site=loja" nunca faria o que o dono pensa.
     *
     * @throws IllegalArgumentException com a frase que ajuda o modelo a se corrigir
     */
    public static Filtro ler(FonteDeGatilho fonte, JsonNode no, Set<String> sitesConhecidos) {
        if (no == null || no.isMissingNode() || no.isNull()) no = tools.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        if (!no.isObject()) throw new IllegalArgumentException("filtro tem de ser um objeto");
        for (var campo : no.propertyNames()) {
            if (!fonte.camposDoFiltro().contains(campo))
                throw new IllegalArgumentException("o filtro '" + campo + "' não vale para " + fonte.chave()
                        + (fonte.camposDoFiltro().isEmpty() ? " (esta fonte não tem filtro)"
                                : "; use " + String.join(", ", new java.util.TreeSet<>(fonte.camposDoFiltro()))));
        }
        String site = Json.texto(no.path("site")).strip();
        if (!site.isEmpty() && !sitesConhecidos.contains(site))
            throw new IllegalArgumentException("site desconhecido: '" + site + "'. Use um de " + sitesConhecidos);
        String nome = Json.texto(no.path("nome")).strip();
        if (nome.length() > 80) throw new IllegalArgumentException("nome do arquivo acima de 80 caracteres");
        String formato = Json.texto(no.path("formato")).strip().toLowerCase(Locale.ROOT);
        if (!formato.isEmpty() && !FORMATOS.contains(formato))
            throw new IllegalArgumentException("formato inválido; use um de " + new java.util.TreeSet<>(FORMATOS));
        String tipo = Json.texto(no.path("tipo")).strip().toLowerCase(Locale.ROOT);
        if (!tipo.isEmpty() && !TIPOS_DA_COTA.contains(tipo))
            throw new IllegalArgumentException("tipo inválido; use antecipado ou reserva");
        if (fonte != FonteDeGatilho.HORARIO) return new Filtro(site, nome, formato, tipo, null, null);

        String texto = Json.texto(no.path("quando")).strip();
        if (texto.isEmpty()) throw new IllegalArgumentException("o horário precisa de 'quando' (AAAA-MM-DDTHH:MM)");
        LocalDateTime quando;
        try { quando = LocalDateTime.parse(texto).withSecond(0).withNano(0); }
        catch (DateTimeParseException e) { throw new IllegalArgumentException("'quando' inválido: use AAAA-MM-DDTHH:MM"); }
        Recorrencia rec = Recorrencia.porChave(Json.texto(no.path("recorrencia")));
        Repeticao repeticao = switch (rec) {
            case NENHUMA -> Repeticao.NENHUMA;
            case DIARIO -> Repeticao.diario();
            case DIAS_UTEIS -> Repeticao.diasUteis();
            case MENSAL -> Repeticao.mensal(quando.getDayOfMonth());
            case SEMANAL -> {
                Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
                for (JsonNode d : no.path("dias_da_semana")) dias.add(diaPorNome(Json.texto(d)));
                yield Repeticao.semanal(dias.isEmpty() ? EnumSet.of(quando.getDayOfWeek()) : dias);
            }
        };
        return new Filtro(null, null, null, null, quando, repeticao);
    }

    /** Se o evento é deste recorte. O horário casa pela regra, no {@link MotorDeGatilhos}. */
    public boolean casa(EventoDeGatilho evento) {
        if (site != null && !site.equals(evento.dado("site"))) return false;
        if (tipo != null && !tipo.equals(evento.dado("tipo"))) return false;
        if (formato != null && !formato.equalsIgnoreCase(evento.dado("formato"))) return false;
        return nome == null || Texto.normalizar(evento.dado("nome")).contains(Texto.normalizar(nome));
    }

    /** Como o evento é dito ao dono: "o site loja cair", "for 08:00, toda segunda". */
    public String descreverEvento(FonteDeGatilho fonte) {
        return switch (fonte) {
            case SITE_CAIU -> site == null ? "um site monitorado cair" : "o site " + site + " cair";
            case SITE_VOLTOU -> site == null ? "um site monitorado voltar" : "o site " + site + " voltar";
            case PC_LIGOU -> "o PC ligar";
            case PC_DESLIGOU -> "o PC desligar";
            case COTA_VOZ -> tipo == null ? "a cota de voz der aviso"
                    : tipo.equals("reserva") ? "a voz entrar na reserva" : "a cota de voz passar do limite de aviso";
            case ARQUIVO_NOVO_NA_ENTRADA -> "chegar arquivo novo na pasta entrada"
                    + (formato == null ? "" : " (" + formato + ")")
                    + (nome == null ? "" : " com '" + nome + "' no nome");
            case TAREFA_FALHOU -> "uma tarefa de fundo falhar";
            case HORARIO -> quando == null ? "chegar o horário"
                    : repeticao.recorrente() ? "for " + HORA.format(quando) + ", " + repeticao.descricao()
                    : "for " + DIA_E_HORA.format(quando);
        };
    }

    private static DayOfWeek diaPorNome(String nome) {
        String limpo = Texto.normalizar(nome);
        for (int i = 0; i < DIAS.length; i++) if (DIAS[i].equals(limpo)) return DayOfWeek.of(i + 1);
        throw new IllegalArgumentException("dia da semana inválido: '" + nome + "'");
    }

    private static String vazioViraNulo(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
