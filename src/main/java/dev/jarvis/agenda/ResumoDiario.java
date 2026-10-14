package dev.jarvis.agenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;

/**
 * Tarefa agendada: todo dia útil de manhã o Jarvis manda o briefing do dia.
 * O horário vem de RESUMO_DIARIO_CRON (use "-" para desligar).
 *
 * A narração com a personalidade é do {@link BriefingService}, e não daqui: assim o resumo das 8h,
 * o /bomdia e a HUD falam exatamente o mesmo briefing.
 */
@Component
public class ResumoDiario {

    private static final Logger log = LoggerFactory.getLogger(ResumoDiario.class);

    private final BriefingService briefing;
    private final TelegramClient telegram;
    private final long chatDoDono;

    public ResumoDiario(BriefingService briefing, TelegramClient telegram, JarvisProperties props) {
        this.briefing = briefing;
        this.telegram = telegram;
        this.chatDoDono = props.telegram().usuarioPermitido();
    }

    @Scheduled(cron = "${jarvis.resumo-diario.cron}", zone = "${jarvis.fuso-horario}")
    public void enviar() {
        if (chatDoDono == 0) {
            return;
        }
        try {
            telegram.enviarMensagem(chatDoDono, briefing.gerar().paraTelegram());
        } catch (Exception e) {
            log.error("Falha ao gerar o briefing diário", e);
            telegram.enviarMensagem(chatDoDono, "Bom dia! Não consegui montar o briefing de hoje: " + e.getMessage());
        }
    }
}
