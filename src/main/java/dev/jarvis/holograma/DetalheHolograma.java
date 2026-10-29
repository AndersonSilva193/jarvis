package dev.jarvis.holograma;

import java.util.Optional;

/**
 * Os três níveis de detalhe que o prompt conhece, com o teto de saída de cada um.
 *
 * <p>O teto tem folga sobre o que o nível pede: uma linha de peça custa de 50 a 70 tokens, e o
 * {@code detalhado} (até 110 linhas) passa de 7 mil sem contar o raciocínio que o modelo ainda faça.
 * Cortar no teto não estraga o objeto — a {@link MontagemDeHolograma} fecha com o que chegou —, mas
 * corta justamente os detalhes, que vêm por último.
 */
public enum DetalheHolograma {

    RAPIDO("rapido", 4_000),
    NORMAL("normal", 8_000),
    DETALHADO("detalhado", 16_000);

    private final String chave;
    private final int maxTokens;

    DetalheHolograma(String chave, int maxTokens) {
        this.chave = chave;
        this.maxTokens = maxTokens;
    }

    /** O que vai na URL e no prompt. */
    public String chave() {
        return chave;
    }

    public int maxTokens() {
        return maxTokens;
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
