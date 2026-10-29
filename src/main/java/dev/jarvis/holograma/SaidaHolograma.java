package dev.jarvis.holograma;

import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * @param troca {@code true} quando o aviso diz que o gerador mudou (Haiku ↔ local): a HUD o mostra
     *              à parte, em vez de somá-lo aos consertos da montagem
     */
    record Aviso(String texto, boolean troca) implements SaidaHolograma {
        public Aviso(String texto) {
            this(texto, false);
        }

        public String evento() { return "aviso"; }

        public Object dados() {
            return troca ? Map.of("texto", texto, "troca", true) : Map.of("texto", texto);
        }
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

    /**
     * O que a geração gastou, para o selo da HUD e para a galeria. Montado em Java de dado real:
     * os tokens são os que a API devolveu, e o custo é uma estimativa pela tabela configurada.
     *
     * @param tokensEntrada {@code null} quando o stream foi cortado antes de a API informar o uso
     *                      (limite de peças, prazo, cliente): melhor omitir do que afirmar zero
     * @param custoUsd      {@code null} sem tokens ou quando quem gerou foi o modelo local (custo zero
     *                      de API, não um dado a mostrar)
     */
    record Resumo(String provedor, String modelo, Long tokensEntrada, Long tokensSaida, Double custoUsd) { }

    /**
     * O fim, com o placar: quantas peças foram para a tela e quantos avisos saíram. O {@code resumo}
     * é acrescentado por quem transmite uma geração real; o exemplo fixo não tem o que informar.
     */
    record Fim(int pecas, int avisos, Resumo resumo) implements SaidaHolograma {
        public Fim(int pecas, int avisos) {
            this(pecas, avisos, null);
        }

        public Fim comResumo(Resumo novo) {
            return new Fim(pecas, avisos, novo);
        }

        public String evento() { return "fim"; }

        /** Sem resumo o corpo é o de sempre; campo que não se sabe fica de fora, não vai como null. */
        public Object dados() {
            if (resumo == null) return this;
            Map<String, Object> corpo = new LinkedHashMap<>();
            corpo.put("pecas", pecas);
            corpo.put("avisos", avisos);
            corpo.put("provedor", resumo.provedor());
            if (!resumo.modelo().isEmpty()) corpo.put("modelo", resumo.modelo());
            if (resumo.tokensEntrada() != null) corpo.put("tokensEntrada", resumo.tokensEntrada());
            if (resumo.tokensSaida() != null) corpo.put("tokensSaida", resumo.tokensSaida());
            if (resumo.custoUsd() != null) corpo.put("custoUsd", resumo.custoUsd());
            return corpo;
        }
    }
}
