package dev.jarvis.inicializacao;

/**
 * A apresentação que a HUD fala quando a tela acende.
 *
 * Além do texto, carrega os números que a própria sequência de abertura mostra enquanto o Jarvis
 * fala — a linha "SISTEMAS MONITORADOS... 3/3" sai de {@code sitesOk}/{@code sitesTotal}. Assim a
 * tela não precisa de uma segunda chamada só para saber o que já foi apurado aqui.
 *
 * @param climaOk  falso quando a fonte do clima não respondeu a tempo; a tela mostra a checagem
 *                 como falha em vez de inventar uma temperatura
 * @param geradoEm ISO-8601 em texto, e não um {@code ZonedDateTime}: o {@code JsonMapper} do
 *                 {@code BeansConfig} é cru, então não dependemos de como ele serializaria data
 */
public record Introducao(
        String texto,
        int sitesOk,
        int sitesTotal,
        boolean climaOk,
        int lembretesHoje,
        String geradoEm) {
}
