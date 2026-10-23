package dev.jarvis.custo;

/**
 * O que um modelo cobra, em dólares por <b>milhão</b> de tokens.
 *
 * Os quatro números são os quatro contadores que a Messages API devolve em {@code usage}: entrada
 * crua, saída, escrita de cache (mais cara que a entrada) e leitura de cache (muito mais barata).
 * Juntar os dois de cache num só esconderia justamente a conta que o prompt cacheado existe para
 * baratear.
 */
public record Preco(double entradaPorMilhao, double saidaPorMilhao,
        double cacheEscritaPorMilhao, double cacheLeituraPorMilhao) {
}
