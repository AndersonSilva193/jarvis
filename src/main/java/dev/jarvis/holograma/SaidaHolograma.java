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

    /** O fim, com o placar: quantas peças foram para a tela e quantos avisos saíram. */
    record Fim(int pecas, int avisos) implements SaidaHolograma {
        public String evento() { return "fim"; }
        public Object dados() { return this; }
    }
}
