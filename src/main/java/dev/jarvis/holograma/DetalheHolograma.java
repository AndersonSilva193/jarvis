package dev.jarvis.holograma;

import java.util.Optional;

/**
 * Os três níveis de detalhe que o prompt conhece.
 *
 * <p>O teto de saída de cada um é configurável ({@code HOLOGRAMA_MAX_TOKENS_*}, padrões 4000, 8000 e
 * 16000 em {@code JarvisProperties.maxTokensDoHolograma}). Ele tem folga sobre o que o nível pede:
 * uma linha de peça custa de 50 a 100 tokens, e o {@code detalhado} (até 110 linhas) passa de 8 mil.
 * Cortar no teto não perde o que chegou — o servidor pede a continuação —, mas cada continuação
 * reenvia o objeto inteiro como entrada.
 */
public enum DetalheHolograma {

    RAPIDO("rapido"),
    NORMAL("normal"),
    DETALHADO("detalhado");

    private final String chave;

    DetalheHolograma(String chave) {
        this.chave = chave;
    }

    /** O que vai na URL e no prompt. */
    public String chave() {
        return chave;
    }

    /** O nível pela chave; ausente é {@link #NORMAL}, e chave desconhecida é vazio. */
    public static Optional<DetalheHolograma> porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return Optional.of(NORMAL);
        }
        for (DetalheHolograma detalhe : values()) {
            if (detalhe.chave.equals(chave.trim())) {
                return Optional.of(detalhe);
            }
        }
        return Optional.empty();
    }
}
