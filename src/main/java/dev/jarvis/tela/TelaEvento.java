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

    public static TelaEvento deCard(CardTela card) {
        return new TelaEvento(CARD, card);
    }

    public static TelaEvento deComando(ComandoTela comando) {
        return new TelaEvento(COMANDO, comando);
    }
}
