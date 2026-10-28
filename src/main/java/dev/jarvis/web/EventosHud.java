package dev.jarvis.web;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.briefing.BriefingGeradoEvento;
import dev.jarvis.briefing.BriefingIniciadoEvento;
import dev.jarvis.conversa.EventoConversa;
import dev.jarvis.conversa.RespostaConversa;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.provedor.ModoDeIaEvento;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEvento;

/**
 * Traduz eventos de domínio de briefing, conversa e tela para as conexões SSE da HUD.
 *
 * Também é quem responde "tem HUD ouvindo?" ao resto do sistema, pela porta
 * {@link PresencaDaHud} — é assim que as ferramentas de tela sabem disso sem conhecer a camada web.
 */
@Component
public class EventosHud implements PresencaDaHud {

    private static final Logger log = LoggerFactory.getLogger(EventosHud.class);
    /** Sem expirar: quem controla a vida da conexão é o cliente (e o keep-alive abaixo). */
    private static final long SEM_TIMEOUT = 0L;

    private final List<SseEmitter> conectados = new CopyOnWriteArrayList<>();
    private final long chatId;
    /** O cliente SSE lento não pode atrasar o 202 nem segurar a execução do assistente. */
    private final ExecutorService transmissao = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("hud-eventos-", 0).factory());

    public EventosHud(JarvisProperties props) { this.chatId = props.telegram().usuarioPermitido(); }

    @EventListener
    public void aoConversar(EventoConversa evento) {
        if (evento.chatId() != chatId) return;
        Object dados = evento.dados() instanceof RespostaConversa resposta ? ConversaDto.de(resposta) : evento.dados();
        // Quem conectou depois recupera por HTTP; não recebe a fila de eventos antigos.
        List<SseEmitter> destinatarios = List.copyOf(conectados);
        transmissao.submit(() -> destinatarios.stream().filter(conectados::contains)
                .forEach(emissor -> enviarPara(emissor, evento.nome(), dados)));
    }

    @PreDestroy public void fechar() { transmissao.shutdownNow(); conectados.forEach(SseEmitter::complete); }

    /**
     * Registra uma conexão nova. A recuperação de estado é feita pelas consultas HTTP da HUD.
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

    /**
     * Card novo, ordem para a tela ou frase para ela falar. Vai pelo mesmo executor da conversa:
     * isto é disparado de dentro do laço de ferramentas do assistente, e uma HUD lenta não pode
     * segurar o pedido.
     *
     * <p>Repassa pelo {@code nome} do evento, então um tipo novo de {@code TelaEvento} chega à HUD
     * sem fiação nova aqui — foi assim que o {@code voz.falar} do despertar entrou.
     */
    @EventListener
    public void aoTela(TelaEvento evento) {
        List<SseEmitter> destinatarios = List.copyOf(conectados);
        transmissao.submit(() -> destinatarios.stream().filter(conectados::contains)
                .forEach(emissor -> enviarPara(emissor, evento.nome(), evento.dados())));
    }

    /**
     * O modo de IA mudou, ou quem pensa entrou ou saiu do fallback. Pelo executor, como o
     * {@link #aoTela}: o seletor publica de dentro de uma chamada de apoio.
     */
    @EventListener
    public void aoModoDeIa(ModoDeIaEvento evento) {
        List<SseEmitter> destinatarios = List.copyOf(conectados);
        transmissao.submit(() -> destinatarios.stream().filter(conectados::contains)
                .forEach(emissor -> enviarPara(emissor, "ia.modo", evento)));
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
        } catch (IOException | RuntimeException e) {
            conectados.remove(emissor);
            log.debug("Conexão SSE caiu ao enviar '{}': {}", nome, e.getMessage());
        }
    }

    /** Quantas HUDs estão ouvindo agora (usado no log, nas ferramentas de tela e no teste). */
    @Override
    public int conexoesAbertas() {
        return conectados.size();
    }
}
