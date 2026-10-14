package dev.jarvis.telegram;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * Fica num loop perguntando ao Telegram "tem mensagem nova?" e repassa para o Assistente.
 * Só responde ao usuário configurado em TELEGRAM_USUARIO_PERMITIDO.
 */
@Component
public class TelegramPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    private final TelegramClient telegram;
    private final Assistente assistente;
    private final BriefingService briefing;
    private final long usuarioPermitido;
    private volatile boolean rodando;
    private Thread thread;

    public TelegramPoller(TelegramClient telegram, Assistente assistente, BriefingService briefing,
            JarvisProperties props) {
        this.telegram = telegram;
        this.assistente = assistente;
        this.briefing = briefing;
        this.usuarioPermitido = props.telegram().usuarioPermitido();
    }

    @Override
    public void start() {
        if (usuarioPermitido == 0) {
            log.warn("TELEGRAM_USUARIO_PERMITIDO não configurado: vou ignorar todas as mensagens e só mostrar "
                    + "nos logs o ID de quem escrever. Copie o seu ID para o .env e reinicie.");
        }
        rodando = true;
        // Thread de plataforma (não-daemon): mantém o processo vivo mesmo sem servidor web.
        thread = Thread.ofPlatform().name("telegram-poller").start(this::loop);
        log.info("Jarvis online, aguardando mensagens no Telegram.");
    }

    @Override
    public void stop() {
        rodando = false;
        if (thread != null) {
            thread.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }

    private void loop() {
        long offset = 0;
        while (rodando) {
            try {
                List<JsonNode> atualizacoes = telegram.buscarAtualizacoes(offset);
                for (JsonNode atualizacao : atualizacoes) {
                    // Avançamos o offset antes de processar: se der erro, a mensagem não volta em loop.
                    offset = Json.numero(atualizacao.path("update_id")) + 1;
                    tratar(atualizacao.path("message"));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Erro ao buscar mensagens do Telegram: {}. Tentando de novo em 5s.", e.getMessage());
                dormir(5_000);
            }
        }
    }

    private void tratar(JsonNode mensagem) {
        if (mensagem.isMissingNode()) {
            return;
        }
        long usuario = Json.numero(mensagem.path("from").path("id"));
        long chatId = Json.numero(mensagem.path("chat").path("id"));
        String texto = Json.texto(mensagem.path("text")).trim();

        if (usuario != usuarioPermitido) {
            log.warn("Mensagem ignorada do usuário {} (não é o usuário permitido).", usuario);
            return;
        }
        if (texto.isEmpty()) {
            telegram.enviarMensagem(chatId, "Por enquanto eu só entendo mensagens de texto.");
            return;
        }

        try {
            switch (texto) {
                case "/start", "/ajuda" -> telegram.enviarMensagem(chatId, """
                        Olá! Sou o Jarvis. Fale comigo normalmente, por exemplo:
                        • "os sites estão no ar?"
                        • "como está o servidor?"
                        • "vai chover hoje?"
                        • "o que tem de novo em tecnologia?"
                        • "me lembra de enviar o currículo às 15h"
                        • "quais lembretes eu tenho?"

                        Comandos:
                        /bomdia mostra o briefing do dia (clima, lembretes, sistemas e notícias)
                        /limpar apaga o histórico da conversa""");
                case "/bomdia" -> {
                    telegram.mostrarDigitando(chatId);
                    telegram.enviarMensagem(chatId, briefing.gerar().paraTelegram());
                }
                case "/limpar" -> {
                    assistente.limparHistorico(chatId);
                    telegram.enviarMensagem(chatId, "Histórico apagado. Começando do zero.");
                }
                default -> {
                    telegram.mostrarDigitando(chatId);
                    telegram.enviarMensagem(chatId, assistente.responder(chatId, texto));
                }
            }
        } catch (Exception e) {
            log.error("Erro ao processar mensagem", e);
            telegram.enviarMensagem(chatId, "Tive um problema para processar isso: " + e.getMessage());
        }
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rodando = false;
        }
    }
}
