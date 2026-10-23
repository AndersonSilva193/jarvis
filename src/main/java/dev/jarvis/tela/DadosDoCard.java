package dev.jarvis.tela;

import java.util.List;

/**
 * O conteúdo de um card da tela em formato estruturado, para a HUD desenhar.
 *
 * Cada tipo de card tem a sua forma, então quem consome faz pattern matching — a interface é
 * selada justamente para o compilador cobrar um caso novo quando um tipo novo aparecer. É o
 * mesmo desenho de {@link dev.jarvis.briefing.DadosSecao}, pelo mesmo motivo.
 *
 * Nada de java.time aqui: o JsonMapper do BeansConfig é cru, então data já vai em texto.
 */
public sealed interface DadosDoCard {

    /**
     * Um resultado de pesquisa com estrutura que vale ver. O {@code resumo} é o mesmo texto que o
     * Jarvis fala, para a tela e a voz nunca divergirem.
     *
     * @param layout       ranking, ficha, lista, linha_do_tempo, destaque ou grafico — é ele que
     *                     diz à HUD qual forma de {@link ItemDoCard} esperar.
     * @param referencia   de quando é a informação, em texto livre ("outubro de 2026").
     * @param grafico      só no layout {@code grafico}, e aí {@code itens} é vazia; null nos
     *                     outros. É campo irmão, e não um item dentro de {@code itens}, para a HUD
     *                     ler sempre do mesmo caminho em vez de desembrulhar um array de um
     *                     elemento só que é de outra natureza.
     * @param consultadoEm quando o Jarvis montou o card, pelo relógio do servidor. Nunca vem do
     *                     modelo: informação de "quando" que o modelo inventa é a pior de todas.
     */
    record Pesquisa(
            String categoria,
            String tema,
            String referencia,
            String layout,
            List<ItemDoCard> itens,
            String resumo,
            List<Fonte> fontes,
            String consultadoEm,
            Grafico grafico) implements DadosDoCard {

        public Pesquisa {
            itens = itens == null ? List.of() : List.copyOf(itens);
            fontes = fontes == null ? List.of() : List.copyOf(fontes);
        }
    }

    /**
     * Um vídeo do YouTube. Serve aos dois tipos de card — video e musica —, porque a forma é a
     * mesma; o que muda é o {@code tipo} do {@link CardTela} e como a HUD apresenta.
     *
     * @param alternativas os outros resultados da busca, para o usuário trocar sem nova consulta
     *                     (e sem gastar outra vez os 100 pontos de cota do search.list).
     */
    record Midia(
            String modo,
            String videoId,
            String titulo,
            String canal,
            String miniatura,
            List<Alternativa> alternativas) implements DadosDoCard {

        public Midia {
            alternativas = alternativas == null ? List.of() : List.copyOf(alternativas);
        }

        public record Alternativa(String videoId, String titulo, String canal, String miniatura) {
        }
    }

    /**
     * A prévia de uma página da web.
     *
     * @param incorporavel se a página aceita ser exibida dentro de outra. Falso quando ela manda
     *                     X-Frame-Options ou frame-ancestors que nos barram: aí a HUD mostra só a
     *                     prévia com o link, em vez de um quadro vazio.
     */
    record Pagina(
            String url,
            String titulo,
            String descricao,
            String imagem,
            String site,
            boolean incorporavel) implements DadosDoCard {
    }

    /** Uma manchete do briefing levada à tela. Mesmos campos de DadosSecao.Noticias.Item. */
    record Noticia(
            String titulo,
            String tema,
            String fonte,
            String link,
            String imagemUrl) implements DadosDoCard {
    }

    /** De onde a informação veio. A URL já foi validada (só http/https) antes de chegar aqui. */
    record Fonte(String nome, String url) {
    }

    /**
     * Uma série de números ao longo de um eixo, para a HUD desenhar em linha ou em barras.
     *
     * Vive dentro de {@link Pesquisa}, no layout {@code grafico}: não é um tipo de card nem um
     * {@link ItemDoCard}, é o conteúdo inteiro de um card de pesquisa que, em vez de itens, mostra
     * um gráfico.
     *
     * @param tipo     "linha" para o que anda no tempo, "barras" para o que compara categorias.
     * @param eixoX    os rótulos, na ordem em que a HUD desenha.
     * @param unidade  "°C", "R$", "%" — o que os valores significam. Pode ser vazia.
     * @param destaque o ponto que a HUD realça, ou null. Índices base zero, já conferidos contra o
     *                 tamanho real das listas.
     * @param origem   "pesquisa" quando os números vieram de uma busca na web, "servidor" quando
     *                 vieram do próprio Jarvis. Quem marca "servidor" é o Java, nunca o modelo:
     *                 número que o modelo apresenta como medido pela casa tem de ter sido medido
     *                 pela casa.
     */
    record Grafico(
            String tipo,
            List<String> eixoX,
            List<Serie> series,
            String unidade,
            Destaque destaque,
            String origem) {

        public Grafico {
            eixoX = eixoX == null ? List.of() : List.copyOf(eixoX);
            series = series == null ? List.of() : List.copyOf(series);
        }

        /** Uma linha do gráfico. Os {@code valores} têm sempre o tamanho do {@code eixoX}. */
        public record Serie(String nome, List<Double> valores) {

            public Serie {
                valores = valores == null ? List.of() : List.copyOf(valores);
            }
        }

        /** O ponto realçado: índice da série e índice no eixo, os dois base zero. */
        public record Destaque(int serie, int indice) {
        }
    }

    /**
     * Um item do card de pesquisa. Qual destes vale é dito pelo {@link Pesquisa#layout()}, que a
     * HUD lê para escolher o desenho.
     */
    sealed interface ItemDoCard {

        /**
         * Uma linha de ranking.
         *
         * @param valor   número para a HUD desenhar barra ou gráfico. Null quando não há número.
         * @param unidade "%", "R$", "km" — o que o valor significa.
         */
        record Posicao(
                int posicao,
                String nome,
                String detalhe,
                Double valor,
                String unidade,
                String etiqueta) implements ItemDoCard {
        }

        /** Uma linha de ficha: rótulo à esquerda, valor à direita. */
        record Campo(String rotulo, String valor) implements ItemDoCard {
        }

        /** Um marco da linha do tempo. A data vai em texto livre, como a fonte a escreveu. */
        record Marco(String data, String texto) implements ItemDoCard {
        }

        /** Um número grande com a sua legenda, para o layout de destaque. */
        record Numero(String valor, String legenda) implements ItemDoCard {
        }

        /** Um item de lista simples. */
        record Linha(String texto) implements ItemDoCard {
        }
    }
}
