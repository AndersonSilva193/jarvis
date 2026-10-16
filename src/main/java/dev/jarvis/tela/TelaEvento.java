package dev.jarvis.tela;

/**
 * Avisa que o Jarvis quer mexer na tela — um card novo ou uma ordem sobre o que já está aberto.
 *
 * Evento de domínio, no mesmo desenho de {@code BriefingGeradoEvento} e {@code EventoConversa}:
 * quem traduz isto para SSE é {@code dev.jarvis.web.EventosHud}, porque a camada web não entra
 * nos serviços. O {@code nome} é o nome do evento no SSE, para a HUD ouvir pelo mesmo rótulo.
 */
public record TelaEvento(String nome, Object dados) {

    public static final String CARD = "tela.card";
    public static final String COMANDO = "tela.comando";
    /** Uma frase pronta para a tela dizer em voz alta; ver {@link FalaDaTela}. */
    public static final String FALA = "voz.falar";

    public static TelaEvento deCard(CardTela card) {
        return new TelaEvento(CARD, card);
    }

    public static TelaEvento deComando(ComandoTela comando) {
        return new TelaEvento(COMANDO, comando);
    }

    /**
     * Uma fala avulsa, sem card nem janela.
     *
     * <p>Vai como objeto com o campo {@code texto} para a HUD ler sempre do mesmo jeito, e para
     * caber mais coisa aqui depois sem quebrar quem já ouve o evento.
     */
    public static TelaEvento deFala(String texto) {
        return new TelaEvento(FALA, new Fala(texto));
    }

    /** O corpo do evento {@link #FALA}. */
    public record Fala(String texto) {
    }
}
