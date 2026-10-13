package dev.jarvis.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.briefing.BriefingGeradoEvento;
import dev.jarvis.briefing.BriefingIniciadoEvento;

/**
 * Mantém as conexões SSE da HUD e empurra para elas tudo que acontece com o briefing,
 * não importa quem disparou: a API, o /bomdia no Telegram, o cron das 8h ou a ferramenta do Claude.
 */
@Component
public class EventosHud {

    private static final Logger log = LoggerFactory.getLogger(EventosHud.class);
    /** Sem expirar: quem controla a vida da conexão é o cliente (e o keep-alive abaixo). */
    private static final long SEM_TIMEOUT = 0L;

    private final List<SseEmitter> conectados = new CopyOnWriteArrayList<>();

    /**
     * Registra uma conexão nova. De propósito não manda nada agora: a HUD fica só com o fundo
     * até um briefing ser pedido (pela API, pelo /bomdia ou pelo cron).
     */
    public SseEmitter registrar() {
        SseEmitter emissor = new SseEmitter(SEM_TIMEOUT);
        emissor.onCompletion(() -> conectados.remove(emissor));
        emissor.onTimeout(() -> conectados.remove(emissor));
        emissor.onError(erro -> conectados.remove(emissor));
        conectados.add(emissor);
        log.info("HUD conectada ao SSE ({} conexão(ões) aberta(s))", conectados.size());
        return emissor;
    }

    @EventListener
    public void aoIniciar(BriefingIniciadoEvento evento) {
        transmitir("gerando", Map.of("status", "gerando"));
    }

    @EventListener
    public void aoGerar(BriefingGeradoEvento evento) {
        transmitir("briefing", BriefingDto.de(evento.briefing(), true));
    }

    /**
     * Comentário de keep-alive. Vai como comentário (":ping") de propósito: atravessa proxy e
     * mantém a conexão viva sem virar evento no EventSource do navegador.
     */
    @Scheduled(fixedRate = 25_000)
    public void manterVivo() {
        for (SseEmitter emissor : conectados) {
            try {
                emissor.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException e) {
                // Cliente foi embora sem avisar: tira da lista e segue.
                conectados.remove(emissor);
            }
        }
    }

    private void transmitir(String nome, Object dados) {
        for (SseEmitter emissor : conectados) {
            enviarPara(emissor, nome, dados);
        }
    }

    private void enviarPara(SseEmitter emissor, String nome, Object dados) {
        try {
            emissor.send(SseEmitter.event().name(nome).data(dados));
        } catch (IOException | IllegalStateException e) {
            conectados.remove(emissor);
            log.debug("Conexão SSE caiu ao enviar '{}': {}", nome, e.getMessage());
        }
    }

    /** Quantas HUDs estão ouvindo agora (usado no log e no teste de fumaça). */
    public int conexoesAbertas() {
        return conectados.size();
    }
}
