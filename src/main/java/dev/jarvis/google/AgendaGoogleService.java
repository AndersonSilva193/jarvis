package dev.jarvis.google;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * Os compromissos de um dia no Google Agenda, em texto curto.
 *
 * <p>Só leitura, com o mesmo OAuth das planilhas ({@link GooglePlanilhasService#ler}). O título de
 * um evento pode ter sido escrito por quem mandou o convite: é dado, então sai sem caractere de
 * controle, sem os sinais {@code <<<}/{@code >>>} e cortado, e quem lê é o modelo como resultado de
 * ferramenta, nunca como instrução.
 */
@Service
public class AgendaGoogleService {

    static final int MAX_EVENTOS = 25;
    static final int MAX_TITULO = 80;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE, dd/MM",
            Locale.of("pt", "BR"));

    private final GooglePlanilhasService google;
    private final JarvisProperties.Google config;
    private final ZoneId zona;
    private final Clock relogio;

    @Autowired
    public AgendaGoogleService(GooglePlanilhasService google, JarvisProperties props) {
        this(google, props, Clock.systemUTC());
    }

    public AgendaGoogleService(GooglePlanilhasService google, JarvisProperties props, Clock relogio) {
        this.google = google;
        this.config = props.google();
        this.zona = props.zona();
        this.relogio = relogio;
    }

    /** De partida, pela regra da lista de ferramentas estável: não olha se já autorizou. */
    public boolean configurado() {
        return google.configurado();
    }

    public LocalDate hoje() {
        return LocalDate.now(relogio.withZone(zona));
    }

    /** Um compromisso. {@code inicio} e {@code fim} vazios no evento de dia inteiro. */
    public record Compromisso(String inicio, String fim, String titulo) {
        boolean diaInteiro() {
            return inicio.isEmpty();
        }
    }

    public List<Compromisso> doDia(LocalDate dia) throws Exception {
        String agenda = config == null || config.agendaId() == null || config.agendaId().isBlank()
                ? "primary" : config.agendaId().trim();
        OffsetDateTime de = dia.atStartOfDay(zona).toOffsetDateTime();
        OffsetDateTime ate = dia.plusDays(1).atStartOfDay(zona).toOffsetDateTime();
        String url = "https://www.googleapis.com/calendar/v3/calendars/" + codificar(agenda)
                + "/events?singleEvents=true&orderBy=startTime&maxResults=" + MAX_EVENTOS
                + "&timeMin=" + codificar(de.toString()) + "&timeMax=" + codificar(ate.toString())
                + "&fields=" + codificar("items(summary,status,start,end)");
        return interpretar(google.ler(url), zona);
    }

    /** O texto que vai ao modelo ou ao agente. */
    public String textoDoDia(LocalDate dia) throws Exception {
        return texto(dia, doDia(dia), hoje());
    }

    static List<Compromisso> interpretar(JsonNode resposta, ZoneId zona) {
        List<Compromisso> lista = new ArrayList<>();
        for (JsonNode item : resposta.path("items")) {
            if ("cancelled".equals(Json.texto(item.path("status")))) continue;
            String titulo = limpar(Json.texto(item.path("summary")));
            String inicio = hora(Json.texto(item.path("start").path("dateTime")), zona);
            String fim = hora(Json.texto(item.path("end").path("dateTime")), zona);
            lista.add(new Compromisso(inicio, fim, titulo.isEmpty() ? "(sem título)" : titulo));
        }
        return lista;
    }

    static String texto(LocalDate dia, List<Compromisso> compromissos, LocalDate hoje) {
        String quando = dia.equals(hoje) ? "Hoje" : dia.equals(hoje.plusDays(1)) ? "Amanhã"
                : dia.format(DIA);
        if (compromissos.isEmpty()) {
            return quando + " a agenda está livre.";
        }
        StringBuilder texto = new StringBuilder(quando).append(", ")
                .append(compromissos.size() == 1 ? "1 compromisso:" : compromissos.size() + " compromissos:");
        for (Compromisso c : compromissos) {
            texto.append("\n- ");
            if (c.diaInteiro()) {
                texto.append("dia inteiro");
            } else {
                texto.append(c.inicio());
                if (!c.fim().isEmpty()) texto.append("–").append(c.fim());
            }
            texto.append(": ").append(c.titulo());
        }
        return texto.toString();
    }

    private static String hora(String dataHora, ZoneId zona) {
        if (dataHora.isBlank()) return "";
        try {
            return OffsetDateTime.parse(dataHora).atZoneSameInstant(zona).format(HORA);
        } catch (RuntimeException e) {
            return "";
        }
    }

    static String limpar(String bruto) {
        String limpo = bruto.replaceAll("\\p{Cntrl}", " ").replace("<<<", " ").replace(">>>", " ")
                .replaceAll("\\s+", " ").trim();
        return limpo.length() > MAX_TITULO ? limpo.substring(0, MAX_TITULO - 1).trim() + "…" : limpo;
    }

    private static String codificar(String valor) {
        return URLEncoder.encode(valor, StandardCharsets.UTF_8);
    }
}
