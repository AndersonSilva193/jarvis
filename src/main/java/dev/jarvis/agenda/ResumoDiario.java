package dev.jarvis.agenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;
import dev.jarvis.telegram.TelegramClient;

/**
 * Tarefa agendada: todo dia útil de manhã o Jarvis manda o briefing do dia.
 * O horário vem de RESUMO_DIARIO_CRON (use "-" para desligar).
 *
 * Os dados continuam vindo prontos do {@link BriefingService} (montados em Java, sem chance de
 * número inventado); o Claude entra só para narrá-los com a personalidade. Se a narração falhar,
 * mandamos o texto cru: a manhã nunca fica sem briefing por causa da API.
 */
@Component
public class ResumoDiario {

    private static final Logger log = LoggerFactory.getLogger(ResumoDiario.class);

    private final BriefingService briefing;
    private final Assistente assistente;
    private final TelegramClient telegram;
    private final long chatDoDono;

    public ResumoDiario(BriefingService briefing, Assistente assistente, TelegramClient telegram,
            JarvisProperties props) {
        this.briefing = briefing;
        this.assistente = assistente;
        this.telegram = telegram;
        this.chatDoDono = props.telegram().usuarioPermitido();
    }

    @Scheduled(cron = "${jarvis.resumo-diario.cron}", zone = "${jarvis.fuso-horario}")
    public void enviar() {
        if (chatDoDono == 0) {
            return;
        }
        String texto;
        try {
            texto = briefing.gerar().paraTexto();
        } catch (Exception e) {
            log.error("Falha ao gerar o briefing diário", e);
            telegram.enviarMensagem(chatDoDono, "Bom dia! Não consegui montar o briefing de hoje: " + e.getMessage());
            return;
        }
        telegram.enviarMensagem(chatDoDono, narrar(texto));
    }

    /** Pede ao Jarvis que leia o briefing em voz de gente. Qualquer problema: devolve o texto cru. */
    private String narrar(String texto) {
        try {
            return assistente.executarTarefa("""
                    Dê o bom dia e apresente o briefing abaixo com as suas palavras, na ordem em que
                    ele veio. Os dados já estão prontos: não altere nenhum número, nome ou horário,
                    não acrescente nada que não esteja aqui e não use ferramenta nenhuma.

                    %s""".formatted(texto), Canal.BRIEFING);
        } catch (Exception e) {
            log.warn("Não consegui narrar o briefing ({}); mandando o texto cru.", e.getMessage());
            return texto;
        }
    }
}
