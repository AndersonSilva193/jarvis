package dev.jarvis.busca;

import java.util.List;

/**
 * Quem procura na web para a {@code buscar_na_web}, sem dizer como.
 *
 * <p>São três, todos com HTTP direto: o Tavily (padrão, 1 crédito por busca), o SearXNG próprio e a
 * busca nativa da Anthropic numa chamada curta ao Claude, que é a última reserva. Quem escolhe a
 * ordem é o {@code BUSCA_PROVEDORES}, e quem percorre é a {@link BuscaNaWeb}.
 */
public interface ProvedorDeBusca {

    /** O nome na grafia do {@code BUSCA_PROVEDORES} e do JSON de custo do dia. */
    String nome();

    /** Se há chave, URL ou definição para tentar. Sem isso o provedor é pulado, sem falha. */
    boolean configurado();

    /**
     * Procura, ou explica em código curto por que não conseguiu.
     *
     * @throws FalhaDeBusca com o motivo ({@code http_432}, {@code rede}...), que é o que vai ao log
     */
    RespostaDaBusca buscar(Consulta consulta) throws FalhaDeBusca, InterruptedException;

    /**
     * O que foi pedido.
     *
     * @param quantidade   de 1 a 5
     * @param recenciaDias só o que foi publicado nos últimos N dias; zero é sem filtro
     */
    record Consulta(String texto, int quantidade, int recenciaDias) {
        public Consulta {
            texto = texto == null ? "" : texto.trim();
            quantidade = Math.clamp(quantidade, 1, 5);
            recenciaDias = Math.max(0, recenciaDias);
        }
    }

    /**
     * Um resultado, já sem o que o provedor tem de próprio.
     *
     * @param data a data de publicação como o provedor a deu, ou vazio
     */
    record ResultadoDaBusca(String titulo, String url, String data, String trecho) {
        public ResultadoDaBusca {
            titulo = titulo == null ? "" : titulo.strip();
            url = url == null ? "" : url.strip();
            data = data == null ? "" : data.strip();
            trecho = trecho == null ? "" : trecho.strip();
        }
    }

    /**
     * A resposta de um provedor.
     *
     * @param resumo o texto que a reserva Anthropic escreveu com o que achou; vazio nos outros dois,
     *               que devolvem os trechos das próprias páginas
     */
    record RespostaDaBusca(String provedor, List<ResultadoDaBusca> resultados, String resumo) {
        public RespostaDaBusca {
            resultados = List.copyOf(resultados);
            resumo = resumo == null ? "" : resumo.strip();
        }
    }

    /** Uma falha com motivo em código, nunca com chave, URL com segredo ou corpo de resposta. */
    final class FalhaDeBusca extends Exception {
        private final String motivo;

        public FalhaDeBusca(String motivo) {
            super(motivo, null, false, false);
            this.motivo = motivo;
        }

        public String motivo() {
            return motivo;
        }
    }
}
