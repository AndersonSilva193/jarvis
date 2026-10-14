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

    /**
     * Uma seção do briefing. Em caso de erro, o conteúdo explica o que faltou.
     *
     * @param dados a mesma informação em formato estruturado, para a HUD desenhar cards. Fica null
     *              quando a seção falhou: aí só resta a prosa explicando o que não deu.
     */
    public record Secao(String titulo, String conteudo, DadosSecao dados, String fala) {

        /** Seção que só tem prosa — é o caso de erro e o dos testes. */
        public Secao(String titulo, String conteudo) {
            this(titulo, conteudo, null, "");
        }

        public Secao(String titulo, String conteudo, DadosSecao dados) {
            this(titulo, conteudo, dados, "");
        }

        /**
         * O que falar desta seção: a narração do Jarvis, quando houver, senão a prosa montada em
         * Java. O {@code conteudo} nunca muda — é ele que a HUD desenha e o Telegram mostra.
         */
        public String paraFala() {
            return fala == null || fala.isBlank() ? conteudo : fala;
        }

        Secao comFala(String narrada) {
            return new Secao(titulo, conteudo, dados, narrada);
        }
    }

    /**
     * @param abertura o cumprimento narrado pelo Jarvis, que a HUD fala no primeiro card. Vazio
     *                 quando não houve narração.
     * @param encerramento as observações finais narradas pelo Jarvis, que a HUD só fala se o
     *                     usuário pedir. Vazio quando não houve narração.
     * @param narracao o briefing inteiro lido pelo Claude (abertura + seções + observações), que é
     *                 o que vai para o Telegram. Os números vêm sempre das seções, nunca do modelo.
     */
    public record Briefing(ZonedDateTime geradoEm, String nomeUsuario, List<Secao> secoes,
            String abertura, String encerramento, String narracao) {

        /** Briefing sem narração — é o caso de falha e o dos testes. */
        public Briefing(ZonedDateTime geradoEm, String nomeUsuario, List<Secao> secoes) {
            this(geradoEm, nomeUsuario, secoes, "", "", "");
        }

        /** Briefing narrado sem abertura própria — usado nos testes de formato. */
        public Briefing(ZonedDateTime geradoEm, String nomeUsuario, List<Secao> secoes, String narracao) {
            this(geradoEm, nomeUsuario, secoes, "", "", narracao);
        }

        /** O briefing em texto simples, montado em Java. É o que vai para o Claude narrar. */
        public String paraTexto() {
            return montar(false);
        }

        /**
         * O que mandar no Telegram: a narração, quando houver. Sem ela, o texto determinístico com
         * os títulos em negrito — o markdown simples que o TelegramClient converte para HTML.
         */
        public String paraTelegram() {
            return temNarracao() ? narracao : montar(true);
        }

        /**
         * O que a HUD fala em voz alta: a narração, quando houver. Sem ela, o texto simples — nunca
         * a versão com negrito, que viraria asterisco na fala.
         */
        public String paraFala() {
            return temNarracao() ? narracao : paraTexto();
        }

        private boolean temNarracao() {
            return narracao != null && !narracao.isBlank();
        }

        private String montar(boolean negrito) {
            StringBuilder sb = new StringBuilder();
            sb.append("Bom dia, ").append(nomeUsuario).append("! ")
                    .append(geradoEm.format(CABECALHO));
            for (Secao secao : secoes) {
                String titulo = secao.titulo().toUpperCase(Locale.of("pt", "BR"));
                sb.append("\n\n").append(negrito ? "**" + titulo + "**" : titulo)
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
    private final NarradorDeBriefing narrador;
    private final JarvisProperties props;
    private final ApplicationEventPublisher eventos;

    private volatile Briefing ultimo;

    public BriefingService(ClimaService clima, NoticiasService noticias, SitesService sites,
            ServidorService servidor, LembreteService lembretes, NarradorDeBriefing narrador,
            JarvisProperties props, ApplicationEventPublisher eventos) {
        this.clima = clima;
        this.noticias = noticias;
        this.sites = sites;
        this.servidor = servidor;
        this.lembretes = lembretes;
        this.narrador = narrador;
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

        // A narração entra antes do evento: assim a HUD, o /bomdia e o resumo das 8h recebem todos
        // o mesmo briefing já com a voz do Jarvis, em vez de cada um narrar por conta.
        String usuario = nomeUsuario();
        Briefing briefing = narrado(agora, usuario, secoes);

        ultimo = briefing;
        eventos.publishEvent(new BriefingGeradoEvento(briefing));
        return briefing;
    }

    /**
     * Passa o briefing pelo Claude e devolve as seções com a fala narrada, mais as observações do
     * fim e a narração inteira, que vai para o Telegram. Narração que não veio: tudo segue com o
     * texto montado em Java, sem nenhum caminho ficar sem briefing.
     */
    private Briefing narrado(ZonedDateTime agora, String usuario, List<Secao> secoes) {
        List<String> falas = narrador.narrar(usuario, secoes);
        if (falas.isEmpty()) {
            return new Briefing(agora, usuario, secoes);
        }

        List<Secao> comFala = new ArrayList<>(secoes.size());
        for (int i = 0; i < secoes.size(); i++) {
            comFala.add(secoes.get(i).comFala(falas.get(i + 1)));
        }
        return new Briefing(agora, usuario, comFala, falas.get(0), falas.get(falas.size() - 1),
                String.join("\n\n", falas));
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
            Conteudo conteudo = montador.montar();
            secoes.add(new Secao(titulo, conteudo.prosa(), conteudo.dados()));
        } catch (Exception e) {
            log.warn("Seção '{}' do briefing falhou: {}", titulo, e.toString());
            secoes.add(new Secao(titulo, "não consegui consultar agora (" + motivo(e) + ")"));
        }
    }

    /** O que um montador devolve: o texto para ler em voz alta e os dados para a HUD desenhar. */
    private record Conteudo(String prosa, DadosSecao dados) {
    }

    private interface Montador {
        Conteudo montar() throws Exception;
    }

    private Conteudo secaoClima() throws Exception {
        var hoje = clima.hoje();
        StringJoiner linhas = new StringJoiner("\n");
        linhas.add(String.format(Locale.of("pt", "BR"), "%s: %.0f °C agora (%s), mínima %.0f °C e máxima %.0f °C",
                hoje.cidade(), hoje.temperaturaAtual(), hoje.descricao(), hoje.minima(), hoje.maxima()));
        String chuva = "Chance de chuva: " + hoje.chanceChuvaPct() + "%";
        if (hoje.chanceChuvaPct() >= 50) {
            chuva += " — vale levar guarda-chuva";
        }
        linhas.add(chuva);
        // Os graus vão arredondados do mesmo jeito que o %.0f da prosa: o card não pode dizer 19
        // enquanto a voz diz 18.
        DadosSecao dados = new DadosSecao.Clima(hoje.cidade(), grau(hoje.temperaturaAtual()),
                grau(hoje.minima()), grau(hoje.maxima()), hoje.chanceChuvaPct(), hoje.descricao());
        return new Conteudo(linhas.toString(), dados);
    }

    private Conteudo secaoLembretes(ZonedDateTime agora) {
        List<Lembrete> deHoje = lembretesDeHoje(lembretes.listar(), agora);
        if (deHoje.isEmpty()) {
            return new Conteudo("Nada marcado para hoje.", new DadosSecao.Lembretes(List.of()));
        }
        StringJoiner linhas = new StringJoiner("\n");
        List<DadosSecao.Lembretes.Item> itens = new ArrayList<>();
        for (Lembrete l : deHoje) {
            String hora = l.quando().withZoneSameInstant(agora.getZone()).format(HORA);
            linhas.add(hora + " " + l.texto());
            itens.add(new DadosSecao.Lembretes.Item(hora, l.texto()));
        }
        return new Conteudo(linhas.toString(), new DadosSecao.Lembretes(itens));
    }

    /** Só os lembretes que caem na data de hoje, no fuso configurado. */
    static List<Lembrete> lembretesDeHoje(List<Lembrete> pendentes, ZonedDateTime agora) {
        LocalDate hoje = agora.toLocalDate();
        return pendentes.stream()
                .filter(l -> l.quando().withZoneSameInstant(agora.getZone()).toLocalDate().equals(hoje))
                .toList();
    }

    private Conteudo secaoSistemas() {
        StringJoiner linhas = new StringJoiner("\n");
        List<Checagem> checagens = sites.checarTodos();
        List<DadosSecao.Sistemas.Site> dosSites = new ArrayList<>();
        if (checagens.isEmpty()) {
            linhas.add("Nenhum site monitorado (configure SITES_MONITORADOS).");
        } else {
            for (Checagem c : checagens) {
                linhas.add(c.nome() + ": " + c.resultado());
                dosSites.add(new DadosSecao.Sistemas.Site(c.nome(), c.resultado()));
            }
        }
        String doServidor = servidor.estado().curto();
        linhas.add(doServidor);
        return new Conteudo(linhas.toString(), new DadosSecao.Sistemas(dosSites, doServidor));
    }

    private Conteudo secaoNoticias() {
        List<Noticia> ultimas = noticias.ultimas(NoticiasService.TODOS, NOTICIAS_NO_BRIEFING);
        if (ultimas.isEmpty()) {
            String vazio = noticias.temas().isEmpty()
                    ? "Nenhum feed configurado (configure NOTICIAS_FEEDS)."
                    : "Nada novo nas últimas 24 horas.";
            return new Conteudo(vazio, new DadosSecao.Noticias(List.of()));
        }
        StringJoiner linhas = new StringJoiner("\n");
        List<DadosSecao.Noticias.Item> itens = new ArrayList<>();
        for (Noticia n : ultimas) {
            String fonte = n.fonte().isBlank() ? n.tema() : n.tema() + " · " + n.fonte();
            linhas.add("• [" + fonte + "] " + n.titulo());
            // O link e a imagem só chegam à HUD por aqui: na prosa não cabem.
            itens.add(new DadosSecao.Noticias.Item(n.titulo(), n.tema(), n.fonte(), n.link(), n.imagemUrl()));
        }
        return new Conteudo(linhas.toString(), new DadosSecao.Noticias(itens));
    }

    /**
     * Graus inteiros, do mesmo jeito que o %.0f da prosa arredonda.
     *
     * O %.0f arredonda para longe do zero no empate (-17,5 vira -18), e o Math.round arredonda
     * para cima (-17,5 viraria -17); o espelho resolve a diferença.
     */
    private static int grau(double celsius) {
        return (int) (celsius < 0 ? -Math.round(-celsius) : Math.round(celsius));
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
