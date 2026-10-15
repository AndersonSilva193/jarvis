package dev.jarvis.tela;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * O retrato do que está aberto na HUD, mandado por ela a cada mudança.
 *
 * É o que permite ao modelo escolher um alvo real em vez de chutar um id. Entra no bloco
 * <b>dinâmico</b> do system — nunca no fixo, que é o cacheado.
 *
 * @param recebidoEm quando chegou, em texto ISO-8601 e pelo relógio do servidor: o JsonMapper do
 *                   projeto é cru, e a hora que a HUD diz não é a que vale.
 */
public record EstadoDaTela(List<Janela> janelas, boolean historicoAberto,
        boolean briefingNaTela, String recebidoEm) {

    /** Os estados de janela que a HUD reporta. */
    public static final Set<String> ESTADOS = Set.of("normal", "minimizado", "maximizado");

    /**
     * Uma janela aberta.
     *
     * @param id      o mesmo id que o servidor gerou no card e a HUD devolve aqui.
     * @param tocando só os cards de vídeo e música usam; nos outros vem falso.
     */
    public record Janela(String id, String tipo, String titulo, String estado,
            boolean fixado, boolean emFoco, boolean tocando) {
    }

    public EstadoDaTela {
        janelas = janelas == null ? List.of() : List.copyOf(janelas);
    }

    /**
     * Se o retrato ainda vale. Estado velho mente para o modelo — ele mandaria fechar uma janela
     * que já não existe —, então depois da validade tratamos como se não houvesse estado nenhum.
     */
    boolean fresco(Instant agora, Duration validade) {
        if (recebidoEm == null || recebidoEm.isBlank()) {
            return false;
        }
        try {
            return Duration.between(Instant.parse(recebidoEm), agora).compareTo(validade) < 0;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
