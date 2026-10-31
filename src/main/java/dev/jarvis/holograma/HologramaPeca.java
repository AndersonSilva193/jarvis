package dev.jarvis.holograma;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Uma peça pronta para desenhar: forma conhecida, números finitos e dentro dos limites, cor
 * válida e id único no objeto. É o que sai do {@link HologramaValidator} — a forma compacta do
 * modelo ({@code "f"}, {@code "p"}, {@code "rep"}...) nunca chega à HUD.
 *
 * <p>Arrays em vez de listas porque é assim que a peça vai ao JSON e ao Three.js; o
 * {@code equals} do record compara referência, então teste confere campo a campo.
 *
 * @param id        snake_case sem acento, único no objeto
 * @param nome      nome em português, para mostrar
 * @param grupo     subconjunto lógico em snake_case ("pistoes"), nunca vazio
 * @param forma     uma de {@link HologramaValidator#FORMAS}
 * @param posicao   x, y, z no cubo de ±6
 * @param rotacao   graus em X, Y, Z (ordem de Euler XYZ do Three.js), em (-180, 180]
 * @param tamanho   na aridade da forma ({@code line} não tem nenhum)
 * @param cor       {@code #rrggbb} em minúsculas
 * @param explosao  direção unitária da vista explodida
 * @param opacidade de 0.05 a 1
 * @param contorno  se a HUD desenha as arestas por cima da malha
 * @param pontos    só em {@code tube} e {@code line}: de 2 a 32 pontos, relativos a {@code posicao}
 * @param serie     o id da primeira cópia, em todas as cópias de um mesmo {@code rep}/{@code rad};
 *                  {@code null} (e fora do JSON) na peça escrita uma vez só. É o que deixa a HUD
 *                  desenhar a série inteira numa chamada só à GPU, sem adivinhar pelo id
 */
public record HologramaPeca(String id, String nome, String grupo, String forma, double[] posicao,
        double[] rotacao, double[] tamanho, String cor, double[] explosao, double opacidade,
        boolean contorno, double[][] pontos, @JsonInclude(JsonInclude.Include.NON_NULL) String serie) {

    /** A peça avulsa, sem série: é o que a galeria gravada antes do campo existir também lê. */
    public HologramaPeca(String id, String nome, String grupo, String forma, double[] posicao,
            double[] rotacao, double[] tamanho, String cor, double[] explosao, double opacidade,
            boolean contorno, double[][] pontos) {
        this(id, nome, grupo, forma, posicao, rotacao, tamanho, cor, explosao, opacidade, contorno, pontos, null);
    }
}
