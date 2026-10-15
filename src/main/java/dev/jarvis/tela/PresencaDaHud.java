package dev.jarvis.tela;

/**
 * Quantas HUDs estão ouvindo agora.
 *
 * Existe para as ferramentas de tela saberem se vale abrir um card sem depender de
 * {@code dev.jarvis.web} — a camada web não entra nos serviços. Quem implementa é o
 * {@code EventosHud}, que já contava as conexões SSE para o log.
 */
public interface PresencaDaHud {

    int conexoesAbertas();

    default boolean conectada() {
        return conexoesAbertas() > 0;
    }

    /** Nenhuma HUD: usado em teste e no construtor de conveniência do SistemaDoJarvis. */
    static PresencaDaHud semTela() {
        return () -> 0;
    }
}
