package dev.jarvis.agente;

import java.util.Optional;
import java.util.Set;
import dev.jarvis.util.Texto;

/** Frases fechadas, antes do modelo e do lock. Pedidos com objeto continuam sendo conversa. */
public final class ComandoDoFreio {
    public enum Acao { PAUSAR, RETOMAR }
    private static final Set<String> PAUSAS = Set.of("para tudo", "pare tudo", "pausa tudo", "pause tudo", "para geral", "pare geral");
    private static final Set<String> RETOMADAS = Set.of("pode voltar", "retoma", "retome", "volta ao normal");
    private ComandoDoFreio() { }
    public static Optional<Acao> reconhecer(String texto) {
        if (texto == null) return Optional.empty();
        String limpo = Texto.normalizar(texto).replaceAll("[\\p{Punct}]+", " ").replaceAll("\\s+", " ").trim();
        if (limpo.isEmpty() || limpo.split(" ").length > 4) return Optional.empty();
        limpo = limpo.replaceFirst("^jarvis ", "").replaceFirst(" jarvis$", "");
        limpo = limpo.replaceFirst("^por favor ", "").replaceFirst(" por favor$", "")
                .replaceFirst(" (agora|ja|ai)$", "").replaceFirst(" jarvis$", "");
        if (PAUSAS.contains(limpo)) return Optional.of(Acao.PAUSAR);
        if (RETOMADAS.contains(limpo)) return Optional.of(Acao.RETOMAR);
        return Optional.empty();
    }
}
