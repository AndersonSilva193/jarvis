package dev.jarvis.web;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.holograma.ExemplosDeHolograma;
import dev.jarvis.holograma.HologramaLineParser;
import dev.jarvis.holograma.MontagemDeHolograma;
import dev.jarvis.holograma.SaidaHolograma;
import jakarta.annotation.PreDestroy;

/**
 * Transmite um objeto de exemplo pelo SSE, peça por peça, como o modelo vai transmitir depois:
 * eventos {@code meta}, {@code peca}, {@code aviso} e {@code fim}, com
 * {@value #PAUSA_ENTRE_PECAS_MS} ms entre uma peça e a seguinte.
 *
 * <p>É um stream próprio, de um objeto só, e não o {@code /api/eventos}: aqui quem pediu é quem
 * recebe, e a conexão fecha no {@code fim}. O exemplo passa pela mesma {@link MontagemDeHolograma}
 * que a resposta do modelo vai passar, então o que a HUD aprende com ele vale para a geração real.
 *
 * <p>O token da HUD é exigido pelo {@link TokenHudFiltro}, em {@code Authorization: Bearer} —
 * esta rota <b>não</b> está na exceção do {@code ?token=}. A Fase 3 lê o stream com {@code fetch},
 * que manda cabeçalho; ver {@code docs/HOLOGRAMA.md}.
 */
@RestController
public class HologramaController {

    private static final Logger log = LoggerFactory.getLogger(HologramaController.class);

    static final long PAUSA_ENTRE_PECAS_MS = 40;
    /** Cem peças a 40 ms são quatro segundos; um minuto é folga para cliente lento. */
    private static final Duration PRAZO_DO_STREAM = Duration.ofMinutes(1);

    private final ExemplosDeHolograma exemplos;
    private final HologramaLineParser parser;
    private final Duration pausa;
    /** Uma thread virtual por stream: a pausa entre peças não pode prender a thread da requisição. */
    private final ExecutorService transmissao = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("holograma-exemplo-", 0).factory());

    @Autowired
    public HologramaController(ExemplosDeHolograma exemplos, HologramaLineParser parser) {
        this(exemplos, parser, Duration.ofMillis(PAUSA_ENTRE_PECAS_MS));
    }

    /** Para teste, que não quer esperar quatro segundos por objeto. */
    HologramaController(ExemplosDeHolograma exemplos, HologramaLineParser parser, Duration pausa) {
        this.exemplos = exemplos;
        this.parser = parser;
        this.pausa = pausa;
    }

    @GetMapping(path = "/api/holograma/exemplo/{nome}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> exemplo(@PathVariable String nome) {
        List<String> linhas = exemplos.linhas(nome).orElse(null);
        if (linhas == null) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emissor = new SseEmitter(PRAZO_DO_STREAM.toMillis());
        AtomicBoolean aberto = new AtomicBoolean(true);
        emissor.onCompletion(() -> aberto.set(false));
        emissor.onTimeout(() -> aberto.set(false));
        emissor.onError(erro -> aberto.set(false));
        transmissao.submit(() -> transmitir(nome, linhas, emissor, aberto));
        return ResponseEntity.ok(emissor);
    }

    @PreDestroy
    void fechar() {
        transmissao.shutdownNow();
    }

    private void transmitir(String nome, List<String> linhas, SseEmitter emissor, AtomicBoolean aberto) {
        MontagemDeHolograma montagem = new MontagemDeHolograma(parser);
        try {
            for (String linha : linhas) {
                if (!enviar(montagem.processar(linha), emissor, aberto)) {
                    return;
                }
            }
            if (enviar(montagem.terminar(), emissor, aberto)) {
                emissor.complete();
            }
        } catch (IOException | IllegalStateException e) {
            // O cliente foi embora no meio: não há a quem avisar.
            log.debug("Stream do holograma '{}' caiu: {}", nome, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emissor.complete();
        }
    }

    /** Envia em ordem, pausando depois de cada peça. Falso quando a conexão já fechou. */
    private boolean enviar(List<SaidaHolograma> saidas, SseEmitter emissor, AtomicBoolean aberto)
            throws IOException, InterruptedException {
        for (SaidaHolograma saida : saidas) {
            if (!aberto.get()) {
                return false;
            }
            emissor.send(SseEmitter.event().name(saida.evento()).data(saida.dados(), MediaType.APPLICATION_JSON));
            if (saida instanceof SaidaHolograma.Peca && !pausa.isZero()) {
                Thread.sleep(pausa);
            }
        }
        return aberto.get();
    }
}
