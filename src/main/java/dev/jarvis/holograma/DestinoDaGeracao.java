package dev.jarvis.holograma;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Para onde vão os eventos de uma geração: o SSE de quem pediu na HUD, ou ninguém.
 *
 * <p>"Ninguém" é o pedido pelo Telegram com a HUD fechada: o objeto é gerado do mesmo jeito, pela
 * mesma montagem, e só a galeria fica com ele. Antes o {@code HologramaService} escrevia direto no
 * {@code SseEmitter}; o adaptador {@link #sse} faz exatamente as mesmas chamadas de antes.
 */
public interface DestinoDaGeracao {

    /** Um evento. Falhar aqui é o cliente que foi embora. */
    void enviar(SaidaHolograma saida) throws IOException;

    /** O comentário {@code :gerando}, que revela o cliente que saiu. */
    void pingar() throws IOException;

    /** A geração acabou; não há mais nada a mandar. */
    void concluir();

    /** O que fazer quando o cliente some (fechou, ou a escrita falhou do lado do container). */
    void aoSair(Runnable acao);

    /** O que fazer quando o prazo do container estoura. */
    void aoEsgotar(Runnable acao);

    static DestinoDaGeracao sse(SseEmitter emitter) {
        return new DestinoDaGeracao() {
            @Override public void enviar(SaidaHolograma saida) throws IOException {
                emitter.send(SseEmitter.event().name(saida.evento()).data(saida.dados(), MediaType.APPLICATION_JSON));
            }
            @Override public void pingar() throws IOException {
                emitter.send(SseEmitter.event().comment("gerando"));
            }
            @Override public void concluir() {
                emitter.complete();
            }
            @Override public void aoSair(Runnable acao) {
                emitter.onCompletion(acao);
                emitter.onError(erro -> acao.run());
            }
            @Override public void aoEsgotar(Runnable acao) {
                emitter.onTimeout(acao);
            }
        };
    }

    /**
     * Sem ninguém ouvindo: só o {@code fim} interessa, e vai para {@code aoFim} — {@code null} se a
     * geração acabou sem nenhum (não deveria; a montagem sempre fecha com um).
     */
    static DestinoDaGeracao mudo(Consumer<SaidaHolograma.Fim> aoFim) {
        AtomicBoolean avisado = new AtomicBoolean();
        return new DestinoDaGeracao() {
            @Override public void enviar(SaidaHolograma saida) {
                if (saida instanceof SaidaHolograma.Fim fim && avisado.compareAndSet(false, true)) {
                    aoFim.accept(fim);
                }
            }
            @Override public void pingar() { }
            @Override public void concluir() {
                if (avisado.compareAndSet(false, true)) aoFim.accept(null);
            }
            @Override public void aoSair(Runnable acao) { }
            @Override public void aoEsgotar(Runnable acao) { }
        };
    }
}
