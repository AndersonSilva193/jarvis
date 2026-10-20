package dev.jarvis.lembretes;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Agenda lembretes, manda no Telegram na hora certa e guarda tudo num arquivo JSON.
 *
 * O dono usa isso como agenda, então o estado não pode morar só na memória: cada mudança é gravada
 * pelo {@link LembretesEmArquivo} e a partida reagenda o que ficou. Os IDs continuam do maior ID
 * salvo — "cancela o 7" não pode acertar um lembrete diferente depois de um restart.
 *
 * Lembrete que venceu com o container parado é avisado na partida com o prefixo "Atrasado:". Acima
 * de 24 horas de atraso vira um resumo único: voltar de um fim de semana fora e receber quarenta
 * mensagens seguidas não é lembrete, é spam.
 */
@Service
public class LembreteService {

    private static final Logger log = LoggerFactory.getLogger(LembreteService.class);

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm");

    /** Acima disso o atraso vira resumo agrupado em vez de uma mensagem por lembrete. */
    private static final Duration ATRASO_QUE_AGRUPA = Duration.ofHours(24);

    /** Teto de saltos ao avançar uma série muito atrasada, para nunca girar sem fim. */
    private static final int MAX_SALTOS = 2000;

    /**
     * Um lembrete pendente. Em série recorrente, {@code quando} é sempre a <b>próxima</b>
     * ocorrência — é ela que está agendada e é ela que o arquivo guarda.
     */
    public record Lembrete(int id, String texto, ZonedDateTime quando, Repeticao repeticao) {

        public Lembrete(int id, String texto, ZonedDateTime quando) {
            this(id, texto, quando, Repeticao.NENHUMA);
        }

        public boolean recorrente() {
            return repeticao != null && repeticao.recorrente();
        }

        /** O mesmo lembrete em outro horário, para avançar a série sem mexer no resto. */
        public Lembrete em(ZonedDateTime outroHorario) {
            return new Lembrete(id, texto, outroHorario, repeticao);
        }
    }

    private record Agendado(Lembrete lembrete, ScheduledFuture<?> tarefa) {
    }

    private final TelegramClient telegram;
    private final long chatDoDono;
    private final ZoneId zona;
    private final Clock relogio;
    private final LembretesEmArquivo arquivo;
    private final ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lembretes");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Agendado> pendentes = new ConcurrentHashMap<>();
    private final AtomicInteger proximoId = new AtomicInteger(1);

    @Autowired
    public LembreteService(TelegramClient telegram, JarvisProperties props, JsonMapper json) {
        this(telegram, props, json, Path.of(props.arquivoDeLembretes()), Clock.systemUTC());
    }

    /** Construtor dos testes: arquivo e relógio dados de fora. */
    public LembreteService(TelegramClient telegram, JarvisProperties props, JsonMapper json,
            Path arquivo, Clock relogio) {
        this.telegram = telegram;
        // Em conversa privada, o ID do chat é o mesmo ID do usuário.
        this.chatDoDono = props.telegram().usuarioPermitido();
        this.zona = props.zona();
        this.relogio = relogio;
        this.arquivo = new LembretesEmArquivo(arquivo, json);
    }

    /**
     * Lê o arquivo, reagenda o que ainda está no futuro e avisa o que venceu durante a parada.
     *
     * Roda no {@code ApplicationReadyEvent} e não no construtor porque avisa pelo Telegram: dar
     * uma volta na rede durante a criação do bean prenderia a subida do contexto.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void recarregar() {
        LembretesEmArquivo.Estado estado = arquivo.carregar();
        proximoId.set(estado.proximoId());
        pendentes.values().forEach(a -> a.tarefa().cancel(false));
        pendentes.clear();

        ZonedDateTime agora = agora();
        List<Lembrete> atrasados = new ArrayList<>();
        for (Lembrete salvo : estado.lembretes()) {
            if (salvo.quando().isAfter(agora)) {
                registrar(salvo);
                continue;
            }
            atrasados.add(salvo);
            // Série recorrente não morre por ter perdido uma volta: ela segue na próxima que
            // ainda está no futuro.
            avancarAte(salvo, agora).ifPresent(this::registrar);
        }
        log.info("Lembretes recarregados de {}: {} pendente(s), {} atrasado(s)",
                arquivo.caminho(), pendentes.size(), atrasados.size());
        persistir();
        avisarAtrasados(atrasados, agora);
    }

    /** Cria um lembrete único. */
    public Lembrete agendar(String texto, ZonedDateTime quando) {
        return agendar(texto, quando, Repeticao.NENHUMA);
    }

    public synchronized Lembrete agendar(String texto, ZonedDateTime quando, Repeticao repeticao) {
        ZonedDateTime agora = agora();
        if (!quando.isAfter(agora)) {
            throw new IllegalArgumentException("esse horário já passou (" + quando + ")");
        }
        if (Duration.between(agora, quando).compareTo(Duration.ofDays(365)) > 0) {
            throw new IllegalArgumentException("só aceito lembretes para os próximos 12 meses");
        }

        Lembrete lembrete = new Lembrete(proximoId.getAndIncrement(), texto, quando,
                repeticao == null ? Repeticao.NENHUMA : repeticao);
        registrar(lembrete);
        persistir();
        log.info("Lembrete #{} agendado para {} ({})", lembrete.id(), quando,
                lembrete.recorrente() ? lembrete.repeticao().descricao() : "uma vez");
        return lembrete;
    }

    public List<Lembrete> listar() {
        return pendentes.values().stream()
                .map(Agendado::lembrete)
                .sorted(Comparator.comparing(Lembrete::quando))
                .toList();
    }

    /** Cancela o lembrete. Sendo recorrente, vai a série inteira — não existe "só esta volta". */
    public synchronized boolean cancelar(int id) {
        Agendado agendado = pendentes.remove(id);
        if (agendado == null) {
            return false;
        }
        agendado.tarefa().cancel(false);
        persistir();
        return true;
    }

    /** O arquivo em uso, para o log e para os testes conferirem onde foi gravado. */
    public Path arquivo() {
        return arquivo.caminho();
    }

    private ZonedDateTime agora() {
        return ZonedDateTime.now(relogio).withZoneSameInstant(zona);
    }

    /** Põe na fila e agenda a tarefa. Já expirado dispara no próximo tique do agendador. */
    private void registrar(Lembrete lembrete) {
        long atrasoMs = Math.max(0, Duration.between(agora(), lembrete.quando()).toMillis());
        ScheduledFuture<?> tarefa = agendador.schedule(() -> disparar(lembrete.id()),
                atrasoMs, TimeUnit.MILLISECONDS);
        pendentes.put(lembrete.id(), new Agendado(lembrete, tarefa));
    }

    /**
     * A primeira ocorrência de uma série que ainda está no futuro, partindo de uma que já passou.
     * Vazio quando o lembrete é único (não há o que avançar).
     */
    private Optional<Lembrete> avancarAte(Lembrete lembrete, ZonedDateTime agora) {
        if (!lembrete.recorrente()) {
            return Optional.empty();
        }
        ZonedDateTime cursor = lembrete.quando();
        for (int i = 0; i < MAX_SALTOS; i++) {
            Optional<ZonedDateTime> proxima = lembrete.repeticao().proxima(cursor);
            if (proxima.isEmpty()) {
                return Optional.empty();
            }
            cursor = proxima.get();
            if (cursor.isAfter(agora)) {
                return Optional.of(lembrete.em(cursor));
            }
        }
        log.warn("Lembrete #{} atrasado além do que vale avançar; a série foi encerrada", lembrete.id());
        return Optional.empty();
    }

    /**
     * Dispara pelo ID e não pelo objeto: entre o agendamento e o disparo o {@code recarregar} pode
     * ter trocado a entrada, e mandar a mensagem de uma série já substituída duplicaria o aviso.
     */
    private void disparar(int id) {
        Lembrete lembrete;
        synchronized (this) {
            Agendado agendado = pendentes.get(id);
            if (agendado == null) {
                return; // cancelado ou recarregado no meio do caminho
            }
            lembrete = agendado.lembrete();
            // A próxima ocorrência é calculada e gravada ANTES de falar: se o container cair entre
            // uma coisa e outra, o pior que acontece é o dono receber o mesmo lembrete duas vezes,
            // nunca a série desaparecer.
            Optional<Lembrete> proximo = avancarAte(lembrete, agora());
            if (proximo.isPresent()) {
                registrar(proximo.get());
            } else {
                pendentes.remove(id);
            }
            persistir();
        }
        telegram.enviarMensagem(chatDoDono, "⏰ Lembrete: " + lembrete.texto()
                + (lembrete.recorrente() ? " (" + lembrete.repeticao().descricao() + ")" : ""));
    }

    /**
     * Avisa o que venceu com o bot fora do ar: até 24 horas de atraso, uma mensagem por lembrete
     * com a hora original; acima disso, um resumo só.
     */
    private void avisarAtrasados(List<Lembrete> atrasados, ZonedDateTime agora) {
        List<Lembrete> recentes = new ArrayList<>();
        List<Lembrete> antigos = new ArrayList<>();
        for (Lembrete l : atrasados) {
            boolean muito = Duration.between(l.quando(), agora).compareTo(ATRASO_QUE_AGRUPA) > 0;
            (muito ? antigos : recentes).add(l);
        }
        recentes.sort(Comparator.comparing(Lembrete::quando));
        antigos.sort(Comparator.comparing(Lembrete::quando));

        for (Lembrete l : recentes) {
            telegram.enviarMensagem(chatDoDono, "⏰ Atrasado: " + l.texto()
                    + " (era para " + l.quando().withZoneSameInstant(zona).format(HORA) + ")");
        }
        if (antigos.isEmpty()) {
            return;
        }
        StringJoiner itens = new StringJoiner("; ");
        for (Lembrete l : antigos) {
            itens.add(l.texto() + " (" + l.quando().withZoneSameInstant(zona).format(DATA_HORA) + ")");
        }
        String quantos = antigos.size() == 1
                ? "1 lembrete venceu"
                : antigos.size() + " lembretes venceram";
        telegram.enviarMensagem(chatDoDono, "⏰ " + quantos + " enquanto eu estava fora: " + itens + ".");
    }

    /** Grava o estado atual. Falha de disco não derruba o lembrete: vira ERROR e a vida segue. */
    private void persistir() {
        try {
            arquivo.salvar(listar(), proximoId.get());
        } catch (IOException | RuntimeException e) {
            log.error("Não consegui gravar os lembretes em {}: {}", arquivo.caminho(), e.getMessage());
        }
    }
}
