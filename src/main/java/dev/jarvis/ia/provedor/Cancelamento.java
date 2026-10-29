package dev.jarvis.ia.provedor;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O botão de parar de uma transmissão, apertado de fora dela.
 *
 * <p>Quem transmite pendura aqui o que corta o stream de verdade — fechar o {@code InputStream} da
 * Anthropic, cancelar o {@code inferir} na fila do PC — e quem decide parar (o cliente que saiu, o
 * prazo, o limite de peças) só chama {@link #cancelar(String)}. A leitura bloqueada sai pelo erro
 * que o próprio corte provoca; ninguém precisa interromper thread.
 *
 * <p>O primeiro motivo vence: o prazo que estoura depois de o cliente sair não reescreve o porquê.
 */
public final class Cancelamento {

    private static final Logger log = LoggerFactory.getLogger(Cancelamento.class);

    private final List<Runnable> acoes = new ArrayList<>();
    private String motivo;

    /** Cancela, se ainda não estava, e roda o que estava pendurado. */
    public void cancelar(String porque) {
        List<Runnable> rodar;
        synchronized (this) {
            if (motivo != null) {
                return;
            }
            motivo = porque == null || porque.isBlank() ? "cancelado" : porque;
            rodar = List.copyOf(acoes);
            acoes.clear();
        }
        rodar.forEach(Cancelamento::rodar);
    }

    public synchronized boolean cancelado() {
        return motivo != null;
    }

    /** O motivo do cancelamento, ou {@code null} enquanto não foi cancelado. */
    public synchronized String motivo() {
        return motivo;
    }

    /** Pendura uma ação de corte. Já cancelado, ela roda na hora, na thread de quem pendurou. */
    public void aoCancelar(Runnable acao) {
        synchronized (this) {
            if (motivo == null) {
                acoes.add(acao);
                return;
            }
        }
        rodar(acao);
    }

    private static void rodar(Runnable acao) {
        try {
            acao.run();
        } catch (RuntimeException e) {
            // Cortar um stream que já caiu pode reclamar; o corte não é o lugar de falhar.
            log.debug("Ação de cancelamento falhou: {}", e.toString());
        }
    }
}
