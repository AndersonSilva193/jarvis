package dev.jarvis.holograma;

/**
 * O que a {@link MontagemDeHolograma} entrega para quem transmite: um nome de evento e o corpo
 * dele. Os nomes são os do SSE ({@code meta}, {@code peca}, {@code aviso}, {@code fim}), e o
 * corpo é o que vai serializado no {@code data:}.
 */
public sealed interface SaidaHolograma {

    String evento();

    Object dados();

    record Meta(HologramaMeta meta) implements SaidaHolograma {
        public String evento() { return "meta"; }
        public Object dados() { return meta; }
    }

    record Peca(HologramaPeca peca) implements SaidaHolograma {
        public String evento() { return "peca"; }
        public Object dados() { return peca; }
    }

    record Aviso(String texto) implements SaidaHolograma {
        public String evento() { return "aviso"; }
        public Object dados() { return this; }
    }

    /**
     * A geração parou antes da hora: prazo, falha do modelo, corte. Sai sempre <b>antes</b> do
     * {@link Fim} — o {@code fim} continua sendo o último evento —, e as peças que já foram ficam.
     * O texto é fixo, montado em Java; o erro do provedor não chega à tela.
     */
    record Erro(String texto) implements SaidaHolograma {
        public String evento() { return "erro"; }
        public Object dados() { return this; }
    }

    /** O fim, com o placar: quantas peças foram para a tela e quantos avisos saíram. */
    record Fim(int pecas, int avisos) implements SaidaHolograma {
        public String evento() { return "fim"; }
        public Object dados() { return this; }
    }
}
