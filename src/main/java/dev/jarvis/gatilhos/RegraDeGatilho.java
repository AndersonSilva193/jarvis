package dev.jarvis.gatilhos;

import java.time.Instant;
import java.util.Objects;

/**
 * Uma regra que o dono ditou: quando a {@code fonte} (recortada pelo {@code filtro}) acontecer, o
 * Jarvis segue a {@code instrucao}. A {@code evidencia} é o trecho literal da mensagem dele que
 * provou que a regra foi pedida — ou, na migração, a linha do {@code SITES_ACOES} de onde ela veio.
 *
 * <p>Imutável: cada mudança devolve outra regra, e quem guarda é o {@link Gatilhos}.
 *
 * @param proximo   só no {@link FonteDeGatilho#HORARIO}: a próxima ocorrência; nulo nas outras
 * @param origem    {@link #ORIGEM_CONVERSA} ou {@link #ORIGEM_SITES_ACOES}
 * @param pedido    o id do pedido que criou a regra (vazio na migração), para "apaga esse gatilho"
 */
public record RegraDeGatilho(String id, long usuario, FonteDeGatilho fonte, Filtro filtro,
        String instrucao, String evidencia, boolean ativa, Instant criadaEm, Instant ultimoDisparo,
        Instant proximo, String origem, String pedido) {

    public static final String ORIGEM_CONVERSA = "conversa";
    public static final String ORIGEM_SITES_ACOES = "sites_acoes";

    public RegraDeGatilho {
        Objects.requireNonNull(id);
        Objects.requireNonNull(fonte);
        Objects.requireNonNull(criadaEm);
        filtro = filtro == null ? Filtro.VAZIO : filtro;
        instrucao = instrucao == null ? "" : instrucao;
        evidencia = evidencia == null ? "" : evidencia;
        origem = origem == null || origem.isBlank() ? ORIGEM_CONVERSA : origem;
        pedido = pedido == null ? "" : pedido;
    }

    /** "o site loja cair", "o PC ligar": o evento como o dono o diria. */
    public String evento() {
        return filtro.descreverEvento(fonte);
    }

    public RegraDeGatilho comAtiva(boolean valor) {
        return new RegraDeGatilho(id, usuario, fonte, filtro, instrucao, evidencia, valor, criadaEm,
                ultimoDisparo, proximo, origem, pedido);
    }

    public RegraDeGatilho comDisparo(Instant quando) {
        return new RegraDeGatilho(id, usuario, fonte, filtro, instrucao, evidencia, ativa, criadaEm,
                quando, proximo, origem, pedido);
    }

    public RegraDeGatilho comProximo(Instant valor) {
        return new RegraDeGatilho(id, usuario, fonte, filtro, instrucao, evidencia, ativa, criadaEm,
                ultimoDisparo, valor, origem, pedido);
    }
}
