package dev.jarvis.agenda;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import dev.jarvis.telegram.TelegramClient;

/**
 * Tarefa agendada: todo dia útil de manhã o Jarvis manda um "bom dia" com a situação dos sistemas.
 * O horário vem de RESUMO_DIARIO_CRON (use "-" para desligar).
 */
@Component
public class ResumoDiario {

    private static final Logger log = LoggerFactory.getLogger(ResumoDiario.class);

    private final Assistente assistente;
    private final TelegramClient telegram;
    private final long chatDoDono;

    public ResumoDiario(Assistente assistente, TelegramClient telegram, JarvisProperties props) {
        this.assistente = assistente;
        this.telegram = telegram;
        this.chatDoDono = props.telegram().usuarioPermitido();
    }

    @Scheduled(cron = "${jarvis.resumo-diario.cron}", zone = "${jarvis.fuso-horario}")
    public void enviar() {
        if (chatDoDono == 0) {
            return;
        }
        try {
            String resumo = assistente.executarTarefa("""
                    Monte meu resumo de bom dia: verifique todos os sites monitorados, o status do servidor
                    e os lembretes pendentes de hoje. Comece com um bom dia curto e destaque qualquer problema.""");
            telegram.enviarMensagem(chatDoDono, resumo);
        } catch (Exception e) {
            log.error("Falha ao gerar o resumo diário", e);
            telegram.enviarMensagem(chatDoDono, "Bom dia! Não consegui montar o resumo de hoje: " + e.getMessage());
        }
    }
}
