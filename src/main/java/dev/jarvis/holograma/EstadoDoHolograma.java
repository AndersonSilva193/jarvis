package dev.jarvis.holograma;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * O retrato do modo Holograma mandado pela HUD a cada mudança.
 *
 * <p>É o que deixa o modelo entender "disso", "essa peça", "mais" e "volta": a linha compacta vai
 * ao bloco <b>dinâmico</b> do system, e as peças ficam só aqui, para a ferramenta resolver o alvo.
 * Elas nunca vão ao prompt — um motor detalhado tem 250.
 *
 * @param objetoId   o id da geração ou do item da galeria; vazio sem objeto
 * @param explosao   o fator atual, 0..1
 * @param selecionada o id da peça selecionada, ou vazio
 * @param isolado    grupos (ou ids) isolados; vazio é "tudo à vista"
 * @param recebidoEm ISO-8601, pelo relógio do servidor, como no {@code EstadoDaTela}
 */
public record EstadoDoHolograma(boolean aberto, String objetoId, String nome, boolean gerando,
        List<Peca> pecas, double explosao, String selecionada, List<String> isolado,
        List<String> escondidos, String vista, String recebidoEm) {

    /** As vistas que a HUD sabe enquadrar. */
    public static final Set<String> VISTAS = Set.of("frente", "lado", "cima", "isometrica", "livre");

    public record Peca(String id, String nome, String grupo) {
    }

    public EstadoDoHolograma {
        objetoId = objetoId == null ? "" : objetoId;
        nome = nome == null ? "" : nome;
        pecas = pecas == null ? List.of() : List.copyOf(pecas);
        selecionada = selecionada == null ? "" : selecionada;
        isolado = isolado == null ? List.of() : List.copyOf(isolado);
        escondidos = escondidos == null ? List.of() : List.copyOf(escondidos);
        vista = vista == null ? "" : vista;
    }

    /** O modo fechado, sem nada: é o que a HUD manda ao fechar a camada. */
    public static EstadoDoHolograma fechado(String recebidoEm) {
        return new EstadoDoHolograma(false, "", "", false, List.of(), 0, "", List.of(), List.of(), "",
                recebidoEm);
    }

    public Optional<Peca> peca(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        return pecas.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    /** Grupo → quantas peças, na ordem em que o grupo apareceu. */
    public Map<String, Integer> grupos() {
        Map<String, Integer> contagem = new LinkedHashMap<>();
        for (Peca peca : pecas) {
            contagem.merge(peca.grupo(), 1, Integer::sum);
        }
        return contagem;
    }

    public Instant instante() {
        try {
            return Instant.parse(recebidoEm);
        } catch (RuntimeException e) {
            return Instant.EPOCH;
        }
    }

    boolean fresco(Instant agora, Duration validade) {
        if (recebidoEm == null || recebidoEm.isBlank()) return false;
        return Duration.between(instante(), agora).compareTo(validade) < 0;
    }
}
