package dev.jarvis.ferramentas;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.lembretes.Recorrencia;
import dev.jarvis.lembretes.Repeticao;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CriarLembreteFerramenta implements Ferramenta {

    private static final DateTimeFormatter EXIBICAO = DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    /** As chaves que o modelo escreve em dias_da_semana, na ordem da semana. */
    private static final String[] DIAS = {
            "segunda", "terca", "quarta", "quinta", "sexta", "sabado", "domingo" };

    private final LembreteService lembretes;
    private final JsonMapper json;
    private final ZoneId zona;

    public CriarLembreteFerramenta(LembreteService lembretes, JsonMapper json, JarvisProperties props) {
        this.lembretes = lembretes;
        this.json = json;
        this.zona = props.zona();
    }

    @Override
    public String nome() {
        return "criar_lembrete";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.LEMBRETES; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Agenda lembrete, aviso ou alarme para pedidos como 'me lembra amanhã'. "
                + "Envia no Telegram na data e hora indicadas. "
                + "Calcule a data e hora exatas a partir do horário atual informado no contexto. "
                + "Para lembrete que se repete, use recorrencia: 'diario' (todos os dias), "
                + "'dias_uteis' (segunda a sexta), 'semanal' (exige dias_da_semana) ou 'mensal' "
                + "(dia do mês de data_hora; dia 31 cai no último dia dos meses curtos). "
                + "Em recorrência, data_hora é a PRIMEIRA ocorrência e define o horário da série.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode props = esquema.putObject("properties");

        ObjectNode texto = props.putObject("texto");
        texto.put("type", "string");
        texto.put("description", "O que deve ser lembrado, curto e claro.");

        ObjectNode dataHora = props.putObject("data_hora");
        dataHora.put("type", "string");
        dataHora.put("description", "Data e hora locais no formato AAAA-MM-DDTHH:MM, ex.: 2026-10-06T15:30. "
                + "Em lembrete recorrente, é a primeira ocorrência.");

        ObjectNode recorrencia = props.putObject("recorrencia");
        recorrencia.put("type", "string");
        ArrayNode tipos = recorrencia.putArray("enum");
        Recorrencia.chaves().forEach(tipos::add);
        recorrencia.put("description", "Como o lembrete se repete. Omita ou use 'nenhuma' para "
                + "lembrete de uma única vez.");

        ObjectNode dias = props.putObject("dias_da_semana");
        dias.put("type", "array");
        dias.put("description", "Só para recorrencia 'semanal': em que dias a série cai.");
        ObjectNode item = dias.putObject("items");
        item.put("type", "string");
        ArrayNode nomes = item.putArray("enum");
        for (String dia : DIAS) {
            nomes.add(dia);
        }

        esquema.putArray("required").add("texto").add("data_hora");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        String texto = Json.texto(entrada.path("texto")).trim();
        String dataHora = Json.texto(entrada.path("data_hora")).trim();
        if (texto.isEmpty()) {
            throw new IllegalArgumentException("o texto do lembrete está vazio");
        }
        ZonedDateTime quando;
        try {
            quando = LocalDateTime.parse(dataHora).atZone(zona);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("data_hora inválida: '" + dataHora + "'. Use AAAA-MM-DDTHH:MM");
        }
        Repeticao repeticao = repeticao(entrada, quando);
        var lembrete = lembretes.agendar(texto, quando, repeticao);
        String repete = repeticao.recorrente() ? ", repetindo " + repeticao.descricao() : "";
        return "Lembrete #" + lembrete.id() + " agendado para " + quando.format(EXIBICAO)
                + repete + ": " + texto;
    }

    /** O dia do mês da série vem de data_hora: é a data que o dono pediu, não um campo à parte. */
    private Repeticao repeticao(JsonNode entrada, ZonedDateTime quando) {
        Recorrencia tipo = Recorrencia.porChave(Json.texto(entrada.path("recorrencia")));
        return switch (tipo) {
            case NENHUMA -> Repeticao.NENHUMA;
            case DIARIO -> Repeticao.diario();
            case DIAS_UTEIS -> Repeticao.diasUteis();
            case MENSAL -> Repeticao.mensal(quando.getDayOfMonth());
            case SEMANAL -> Repeticao.semanal(diasDaSemana(entrada, quando));
        };
    }

    /** Sem dias_da_semana, a série cai no dia da semana da primeira ocorrência. */
    private Set<DayOfWeek> diasDaSemana(JsonNode entrada, ZonedDateTime quando) {
        Set<DayOfWeek> dias = EnumSet.noneOf(DayOfWeek.class);
        for (JsonNode no : entrada.path("dias_da_semana")) {
            dias.add(diaPorNome(Json.texto(no)));
        }
        return dias.isEmpty() ? EnumSet.of(quando.getDayOfWeek()) : dias;
    }

    private static DayOfWeek diaPorNome(String nome) {
        String limpo = nome == null ? "" : nome.trim().toLowerCase();
        for (int i = 0; i < DIAS.length; i++) {
            if (DIAS[i].equals(limpo)) {
                return DayOfWeek.of(i + 1);
            }
        }
        throw new IllegalArgumentException("dia da semana inválido: '" + nome + "'");
    }
}
