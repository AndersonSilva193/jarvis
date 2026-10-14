package dev.jarvis.voz;

import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;

/**
 * Quanto de voz o dia consumiu: caracteres enviados, acertos de cache e trocas de provedor.
 *
 * Uma linha por dia na virada. Serve para responder "por que a cota sumiu tão rápido?" olhando o
 * log, sem precisar do painel da ElevenLabs.
 */
@Component
public class DiarioDeVoz {

    private static final Logger log = LoggerFactory.getLogger(DiarioDeVoz.class);

    private final JarvisProperties props;
    private final AtomicLong caracteres = new AtomicLong();
    private final AtomicLong acertosDeCache = new AtomicLong();
    private final AtomicLong trocas = new AtomicLong();

    public DiarioDeVoz(JarvisProperties props) {
        this.props = props;
    }

    public void sintetizou(int caracteresEnviados) {
        caracteres.addAndGet(caracteresEnviados);
    }

    public void acertouCache() {
        acertosDeCache.incrementAndGet();
    }

    public void trocouDeProvedor() {
        trocas.incrementAndGet();
    }

    @Scheduled(cron = "0 0 0 * * *", zone = "${jarvis.fuso-horario}")
    public void fecharODia() {
        long enviados = caracteres.getAndSet(0);
        long acertos = acertosDeCache.getAndSet(0);
        long trocou = trocas.getAndSet(0);
        if (enviados == 0 && acertos == 0 && trocou == 0) {
            return;
        }
        log.info("Voz em {}: {} caracteres sintetizados, {} acertos de cache, {} trocas de provedor",
                LocalDate.now(props.zona()).minusDays(1), enviados, acertos, trocou);
    }
}
