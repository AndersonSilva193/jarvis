package dev.jarvis.briefing;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.StringJoiner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.NoticiasService;
import dev.jarvis.briefing.fontes.NoticiasService.Noticia;
import dev.jarvis.briefing.fontes.ServidorService;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.briefing.fontes.SitesService.Checagem;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.lembretes.LembreteService.Lembrete;

/**
 * Monta o briefing do dia juntando todas as fontes: clima, lembretes de hoje, sistemas e notícias.
 *
 * É texto montado em Java, sem passar pelo Claude: não custa tokens, sai sempre no mesmo formato
 * e nenhum número aqui pode ser inventado. Cada fonte é coletada em seu próprio try/catch, então
 * uma API fora do ar tira apenas a sua seção, nunca o briefing inteiro.
 */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);

    /** Quantas manchetes entram no briefing (o radar completo fica na ferramenta de notícias). */
    private static final int NOTICIAS_NO_BRIEFING = 3;
    /** Depois disso, "o briefing de hoje" é remontado: ninguém quer a temperatura da manhã às 18h. */
    private static final Duration VALIDADE = Duration.ofMinutes(30);

    private static final DateTimeFormatter CABECALHO =
            DateTimeFormatter.ofPattern("EEEE, dd/MM 'às' HH:mm", Locale.of("pt", "BR"));
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");

    /** Uma seção do briefing. Em caso de erro, o conteúdo explica o que faltou. */
    public record Secao(String titulo, String conteudo) {
    }

    public record Briefing(ZonedDateTime geradoEm, String nomeUsuario, List<Secao> secoes) {

        /** O briefing em texto simples, pronto para mandar no Telegram. */
        public String paraTexto() {
            StringBuilder sb = new StringBuilder();
            sb.append("Bom dia, ").append(nomeUsuario).append("! ")
                    .append(geradoEm.format(CABECALHO));
            for (Secao secao : secoes) {
                sb.append("\n\n").append(secao.titulo().toUpperCase(Locale.of("pt", "BR")))
                        .append("\n").append(secao.conteudo());
            }
            return sb.toString();
        }
    }

    private final ClimaService clima;
    private final NoticiasService noticias;
    private final SitesService sites;
    private final ServidorService servidor;
    private final LembreteService lembretes;
    private final JarvisProperties props;
    private final ApplicationEventPublisher eventos;

    private volatile Briefing ultimo;

    public BriefingService(ClimaService clima, NoticiasService noticias, SitesService sites,
            ServidorService servidor, LembreteService lembretes, JarvisProperties props,
            ApplicationEventPublisher eventos) {
        this.clima = clima;
        this.noticias = noticias;
        this.sites = sites;
        this.servidor = servidor;
        this.lembretes = lembretes;
        this.props = props;
        this.eventos = eventos;
    }

    /** Monta um briefing novo, agora, consultando todas as fontes. */
    public Briefing gerar() {
        // Avisa antes de começar: quem está com a HUD aberta mostra "gerando" durante a coleta.
        eventos.publishEvent(new BriefingIniciadoEvento());

        ZonedDateTime agora = ZonedDateTime.now(props.zona());
        List<Secao> secoes = new ArrayList<>();

        adicionar(secoes, "clima", this::secaoClima);
        adicionar(secoes, "lembretes de hoje", () -> secaoLembretes(agora));
        adicionar(secoes, "sistemas", this::secaoSistemas);
        adicionar(secoes, "notícias", this::secaoNoticias);

        Briefing briefing = new Briefing(agora, nomeUsuario(), secoes);
        ultimo = briefing;
        eventos.publishEvent(new BriefingGeradoEvento(briefing));
        return briefing;
    }

    /** O último briefing montado neste processo, se já houve algum. */
    public Optional<Briefing> ultimo() {
        return Optional.ofNullable(ultimo);
    }

    /** O briefing de hoje, remontado quando o guardado já passou de meia hora. */
    public Briefing deHoje() {
        Briefing guardado = ultimo;
        if (guardado != null
                && guardado.geradoEm().toLocalDate().equals(LocalDate.now(props.zona()))
                && Duration.between(guardado.geradoEm().toInstant(), Instant.now()).compareTo(VALIDADE) < 0) {
            return guardado;
        }
        return gerar();
    }

    /** Roda um montador de seção isolando a falha: o que quebra é só aquela seção. */
    private void adicionar(List<Secao> secoes, String titulo, Montador montador) {
        try {
            secoes.add(new Secao(titulo, montador.montar()));
        } catch (Exception e) {
            log.warn("Seção '{}' do briefing falhou: {}", titulo, e.toString());
            secoes.add(new Secao(titulo, "não consegui consultar agora (" + motivo(e) + ")"));
        }
    }

    private interface Montador {
        String montar() throws Exception;
    }

    private String secaoClima() throws Exception {
        var hoje = clima.hoje();
        StringJoiner linhas = new StringJoiner("\n");
        linhas.add(String.format(Locale.of("pt", "BR"), "%s: %.0f °C agora (%s), mínima %.0f °C e máxima %.0f °C",
                hoje.cidade(), hoje.temperaturaAtual(), hoje.descricao(), hoje.minima(), hoje.maxima()));
        String chuva = "Chance de chuva: " + hoje.chanceChuvaPct() + "%";
        if (hoje.chanceChuvaPct() >= 50) {
            chuva += " — vale levar guarda-chuva";
        }
        linhas.add(chuva);
        return linhas.toString();
    }

    private String secaoLembretes(ZonedDateTime agora) {
        List<Lembrete> deHoje = lembretesDeHoje(lembretes.listar(), agora);
        if (deHoje.isEmpty()) {
            return "Nada marcado para hoje.";
        }
        StringJoiner linhas = new StringJoiner("\n");
        for (Lembrete l : deHoje) {
            linhas.add(l.quando().withZoneSameInstant(agora.getZone()).format(HORA) + " " + l.texto());
        }
        return linhas.toString();
    }

    /** Só os lembretes que caem na data de hoje, no fuso configurado. */
    static List<Lembrete> lembretesDeHoje(List<Lembrete> pendentes, ZonedDateTime agora) {
        LocalDate hoje = agora.toLocalDate();
        return pendentes.stream()
                .filter(l -> l.quando().withZoneSameInstant(agora.getZone()).toLocalDate().equals(hoje))
                .toList();
    }

    private String secaoSistemas() {
        StringJoiner linhas = new StringJoiner("\n");
        List<Checagem> checagens = sites.checarTodos();
        if (checagens.isEmpty()) {
            linhas.add("Nenhum site monitorado (configure SITES_MONITORADOS).");
        } else {
            for (Checagem c : checagens) {
                linhas.add(c.nome() + ": " + c.resultado());
            }
        }
        linhas.add(servidor.estado().curto());
        return linhas.toString();
    }

    private String secaoNoticias() {
        List<Noticia> ultimas = noticias.ultimas(NoticiasService.TODOS, NOTICIAS_NO_BRIEFING);
        if (ultimas.isEmpty()) {
            return noticias.temas().isEmpty()
                    ? "Nenhum feed configurado (configure NOTICIAS_FEEDS)."
                    : "Nada novo nas últimas 24 horas.";
        }
        StringJoiner linhas = new StringJoiner("\n");
        for (Noticia n : ultimas) {
            String fonte = n.fonte().isBlank() ? n.tema() : n.tema() + " · " + n.fonte();
            linhas.add("• [" + fonte + "] " + n.titulo());
        }
        return linhas.toString();
    }

    private String nomeUsuario() {
        String nome = props.nomeUsuario();
        return nome == null || nome.isBlank() ? "chefe" : nome;
    }

    private static String motivo(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
