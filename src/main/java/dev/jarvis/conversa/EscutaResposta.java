package dev.jarvis.conversa;

import java.util.List;

/** Pedido para a HUD ouvir o usuário depois que a fala do turno terminar. */
public record EscutaResposta(int tempoSegundos, List<String> opcoes, String motivo) {
    public EscutaResposta {
        opcoes = opcoes == null ? List.of() : List.copyOf(opcoes);
    }
}
