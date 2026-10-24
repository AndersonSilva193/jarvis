package dev.jarvis.tela;

import java.util.List;

/**
 * Monta o card de gráfico cujos números são do próprio Jarvis.
 *
 * É o irmão do {@link MontadorDeCardDePesquisa}, e a diferença é de onde vem o conteúdo: lá se
 * valida JSON escrito pelo modelo, aqui se valida lista montada em Java. Por isso este é o único
 * caminho que marca {@code origem = "servidor"} — o outro recusa essa palavra de propósito.
 *
 * O que não se exige aqui: {@code fontes} e {@code referencia}. Eles existem no gráfico de
 * pesquisa para o dono poder conferir um número que veio da web; um número medido pela casa tem o
 * {@code consultadoEm} do relógio do servidor, e apontar o Jarvis como fonte de si mesmo não diria
 * nada a ninguém.
 *
 * Falha aqui é erro de programação, não do modelo — ele não escreve nada que chegue a este ponto.
 * Então as mensagens nomeiam o campo e o valor, para o teste e o log dizerem o que aconteceu.
 */
public final class MontadorDeCardDeDados {

    private MontadorDeCardDeDados() {
    }

    /**
     * O card pronto para publicar, com id sorteado.
     *
     * @param origemDoCard o canal de onde veio o pedido (telegram, hud...), não a origem dos
     *                     números — essa é sempre "servidor" aqui.
     */
    public static CardTela montar(String titulo, String categoria, String tema, String resumo,
            String tipo, List<String> eixoX, List<DadosDoCard.Grafico.Serie> series,
            String unidade, DadosDoCard.Grafico.Destaque destaque, String origemDoCard,
            String consultadoEm) {
        DadosDoCard.Grafico grafico = grafico(tipo, eixoX, series, unidade, destaque);
        DadosDoCard.Pesquisa dados = new DadosDoCard.Pesquisa(
                exigir(categoria, "categoria", MontadorDeCardDePesquisa.MAX_CATEGORIA),
                exigir(tema, "tema", MontadorDeCardDePesquisa.MAX_CATEGORIA),
                "",
                MontadorDeCardDePesquisa.GRAFICO,
                List.of(),
                exigir(resumo, "resumo", MontadorDeCardDePesquisa.MAX_RESUMO),
                List.of(),
                consultadoEm,
                grafico);
        return CardTela.agora(CardTela.PESQUISA,
                exigir(titulo, "titulo", MontadorDeCardDePesquisa.MAX_TITULO), dados, origemDoCard);
    }

    /**
     * O gráfico do servidor, conferido contra os mesmos limites do gráfico de pesquisa.
     *
     * Os tetos são da HUD, não de quem produziu os números: 25 pontos numa linha viram um rabisco
     * do mesmo jeito, venham eles da web ou da Open-Meteo.
     */
    static DadosDoCard.Grafico grafico(String tipo, List<String> eixoX,
            List<DadosDoCard.Grafico.Serie> series, String unidade,
            DadosDoCard.Grafico.Destaque destaque) {
        if (!MontadorDeCardDePesquisa.TIPOS_GRAFICO.contains(tipo)) {
            throw new IllegalArgumentException(
                    "tipo do gráfico deve ser linha ou barras, não '" + tipo + "'");
        }
        if (eixoX == null || eixoX.isEmpty()) {
            throw new IllegalArgumentException("gráfico sem rótulos no eixoX");
        }
        boolean linha = "linha".equals(tipo);
        int minimo = linha ? MontadorDeCardDePesquisa.MIN_PONTOS_LINHA : 1;
        int maximo = linha ? MontadorDeCardDePesquisa.MAX_PONTOS_LINHA
                : MontadorDeCardDePesquisa.MAX_BARRAS;
        if (eixoX.size() < minimo || eixoX.size() > maximo) {
            throw new IllegalArgumentException("gráfico de " + tipo + " precisa de " + minimo
                    + " a " + maximo + (linha ? " pontos" : " categorias") + " no eixoX; são "
                    + eixoX.size());
        }
        for (int i = 0; i < eixoX.size(); i++) {
            String rotulo = eixoX.get(i);
            if (rotulo == null || rotulo.isBlank()) {
                throw new IllegalArgumentException("eixoX " + (i + 1) + ": rótulo vazio");
            }
            if (rotulo.length() > MontadorDeCardDePesquisa.MAX_ROTULO_EIXO) {
                throw new IllegalArgumentException("eixoX " + (i + 1) + ": rótulo '" + rotulo
                        + "' acima de " + MontadorDeCardDePesquisa.MAX_ROTULO_EIXO + " caracteres");
            }
        }

        if (series == null || series.isEmpty()) {
            throw new IllegalArgumentException("gráfico sem séries");
        }
        if (series.size() > MontadorDeCardDePesquisa.MAX_SERIES) {
            throw new IllegalArgumentException("no máximo " + MontadorDeCardDePesquisa.MAX_SERIES
                    + " séries; são " + series.size());
        }
        for (DadosDoCard.Grafico.Serie serie : series) {
            if (serie == null) {
                throw new IllegalArgumentException("série nula");
            }
            String nome = exigir(serie.nome(), "nome da série",
                    MontadorDeCardDePesquisa.MAX_NOME_SERIE);
            if (serie.valores().size() != eixoX.size()) {
                throw new IllegalArgumentException("série '" + nome + "': " + serie.valores().size()
                        + " valores para " + eixoX.size() + " pontos do eixoX");
            }
            for (int v = 0; v < serie.valores().size(); v++) {
                Double valor = serie.valores().get(v);
                if (valor == null || !Double.isFinite(valor)) {
                    throw new IllegalArgumentException("série '" + nome + "', valor " + (v + 1)
                            + ": " + valor + " não é número finito");
                }
            }
        }

        String medida = unidade == null ? "" : unidade.trim();
        if (medida.length() > MontadorDeCardDePesquisa.MAX_UNIDADE) {
            throw new IllegalArgumentException("unidade '" + medida + "' acima de "
                    + MontadorDeCardDePesquisa.MAX_UNIDADE + " caracteres");
        }
        if (destaque != null && (destaque.serie() < 0 || destaque.serie() >= series.size())) {
            throw new IllegalArgumentException("destaque: série " + destaque.serie() + " não existe");
        }
        if (destaque != null && (destaque.indice() < 0 || destaque.indice() >= eixoX.size())) {
            throw new IllegalArgumentException("destaque: ponto " + destaque.indice() + " não existe");
        }
        return new DadosDoCard.Grafico(tipo, eixoX, series, medida, destaque,
                MontadorDeCardDePesquisa.DO_SERVIDOR);
    }

    private static String exigir(String valor, String campo, int max) {
        String limpo = valor == null ? "" : valor.trim();
        if (limpo.isEmpty()) {
            throw new IllegalArgumentException("falta o campo '" + campo + "'");
        }
        if (limpo.length() > max) {
            throw new IllegalArgumentException(
                    "'" + campo + "' acima de " + max + " caracteres (" + limpo.length() + ")");
        }
        return limpo;
    }
}
