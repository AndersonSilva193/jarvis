package dev.jarvis.lembretes;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;

/**
 * Agenda lembretes e manda no Telegram na hora certa.
 * Atenção: ficam só em memória, então somem se o bot reiniciar. Um ótimo próximo passo é salvar no banco.
 */
@Service
public class LembreteService {

    private static final Logger log = LoggerFactory.getLogger(LembreteService.class);

    public record Lembrete(int id, String texto, ZonedDateTime quando) {
    }

    private record Agendado(Lembrete lembrete, ScheduledFuture<?> tarefa) {
    }

    private final TelegramClient telegram;
    private final long chatDoDono;
    private final ScheduledExecutorService agendador = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lembretes");
        t.setDaemon(true);
        return t;
    });
    private final Map<Integer, Agendado> pendentes = new ConcurrentHashMap<>();
    private final AtomicInteger proximoId = new AtomicInteger(1);

    public LembreteService(TelegramClient telegram, JarvisProperties props) {
        this.telegram = telegram;
        // Em conversa privada, o ID do chat é o mesmo ID do usuário.
        this.chatDoDono = props.telegram().usuarioPermitido();
    }

    public Lembrete agendar(String texto, ZonedDateTime quando) {
        long atrasoMs = Duration.between(ZonedDateTime.now(quando.getZone()), quando).toMillis();
        if (atrasoMs <= 0) {
            throw new IllegalArgumentException("esse horário já passou (" + quando + ")");
        }
        if (atrasoMs > Duration.ofDays(365).toMillis()) {
            throw new IllegalArgumentException("só aceito lembretes para os próximos 12 meses");
        }

        Lembrete lembrete = new Lembrete(proximoId.getAndIncrement(), texto, quando);
        ScheduledFuture<?> tarefa = agendador.schedule(() -> disparar(lembrete), atrasoMs, TimeUnit.MILLISECONDS);
        pendentes.put(lembrete.id(), new Agendado(lembrete, tarefa));
        if (tarefa.isDone()) {
            pendentes.remove(lembrete.id()); // disparou antes de registrarmos (horário muito próximo)
        }
        log.info("Lembrete #{} agendado para {}", lembrete.id(), quando);
        return lembrete;
    }

    public List<Lembrete> listar() {
        return pendentes.values().stream()
                .map(Agendado::lembrete)
                .sorted(Comparator.comparing(Lembrete::quando))
                .toList();
    }

    public boolean cancelar(int id) {
        Agendado agendado = pendentes.remove(id);
        if (agendado == null) {
            return false;
        }
        agendado.tarefa().cancel(false);
        return true;
    }

    private void disparar(Lembrete lembrete) {
        pendentes.remove(lembrete.id());
        telegram.enviarMensagem(chatDoDono, "⏰ Lembrete: " + lembrete.texto());
    }
}
