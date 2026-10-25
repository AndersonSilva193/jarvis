package dev.jarvis.diario;

/**
 * De onde veio o pedido que levou à ação.
 *
 * O canal chega às ferramentas como {@code String} minúscula, pelo {@code OuvinteProgresso.canal()}
 * — a ferramenta não recebe o {@code Canal}, de propósito. Daí o {@link #porCanal(String)}.
 *
 * <p>{@link #ANOTADOR} não é canal nenhum: é o Jarvis agindo sobre a conversa por conta própria,
 * fora de qualquer turno. {@link #DESCONHECIDA} cobre o que não soube se identificar, e aparece
 * como "origem não registrada" em vez de virar "telegram" por descuido.
 */
public enum OrigemDaAcao {

    TELEGRAM("telegram"),
    HUD("hud"),
    ANOTADOR("anotador"),
    TAREFA("tarefa"),
    DESCONHECIDA("desconhecida");

    private final String chave;

    OrigemDaAcao(String chave) {
        this.chave = chave;
    }

    public String chave() {
        return chave;
    }

    /** A origem daquela chave, ou {@code null} — chave desconhecida é linha a descartar. */
    public static OrigemDaAcao porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return null;
        }
        String procurada = chave.trim();
        for (OrigemDaAcao origem : values()) {
            if (origem.chave.equals(procurada)) {
                return origem;
            }
        }
        return null;
    }

    /**
     * A origem do canal de onde veio o pedido. Canal vazio ou que não conhecemos vira
     * {@link #DESCONHECIDA}: o diário registra o que houve mesmo sem saber de onde veio.
     */
    public static OrigemDaAcao porCanal(String canal) {
        OrigemDaAcao achada = porChave(canal);
        // "briefing" é canal, mas nada no briefing escreve em disco; se um dia escrever, é melhor
        // aparecer como não registrada do que ser contada como pedido do dono.
        return achada == null || achada == ANOTADOR ? DESCONHECIDA : achada;
    }
}
