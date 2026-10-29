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
    /**
     * A reserva da {@code buscar_na_web} que pesquisa pela busca nativa numa chamada curta. Não é
     * {@link #CONVERSA}: esta ligaria o cache automático numa chamada que nunca se repete.
     */
    BUSCA("busca"),
    COMPUTER_USE("computer_use"),
    /**
     * A geração de um holograma: uma chamada só, mas a de saída mais longa do Jarvis — um objeto
     * detalhado passa de dez mil tokens. Balde próprio para o dono ver quanto cada objeto custa.
     */
    HOLOGRAMA("holograma");

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
