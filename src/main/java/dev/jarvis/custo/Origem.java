package dev.jarvis.custo;

/**
 * De onde veio a chamada ao modelo que gastou dinheiro.
 *
 * A constante é Java ({@code COMPUTER_USE}); a {@link #chave()} é o que vai para o JSON do dia e
 * para o texto falado ({@code computer_use}). É o mesmo par de
 * {@link dev.jarvis.lembretes.Recorrencia}, e existe pelo mesmo motivo: o arquivo de ontem tem de
 * continuar legível amanhã, mesmo que alguém renomeie uma constante.
 *
 * <p>Não há origem {@code tarefa}: o canal {@code Canal.TAREFA} passa pelo mesmo
 * {@code Assistente} das conversas e é contado como {@link #CONVERSA}.
 */
public enum Origem {

    CONVERSA("conversa"),
    BRIEFING("briefing"),
    INICIALIZACAO("inicializacao"),
    ANOTADOR("anotador"),
    CLASSIFICADOR("classificador"),
    COMPUTER_USE("computer_use");

    private final String chave;

    Origem(String chave) {
        this.chave = chave;
    }

    public String chave() {
        return chave;
    }

    /** A origem daquela chave, ou {@code null} — chave desconhecida é linha a descartar, não erro. */
    public static Origem porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return null;
        }
        String procurada = chave.trim();
        for (Origem origem : values()) {
            if (origem.chave.equals(procurada)) {
                return origem;
            }
        }
        return null;
    }
}
