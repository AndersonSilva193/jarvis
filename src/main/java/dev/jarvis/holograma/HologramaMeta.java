package dev.jarvis.holograma;

/**
 * A primeira linha do objeto, já normalizada: textos aparados, sem caractere de controle e
 * cortados no tamanho que a HUD mostra.
 *
 * @param nome      o nome do objeto ("Motor V8"); nunca vazio
 * @param categoria uma palavra ("mecanica"); pode ser vazia
 * @param descricao uma frase; pode ser vazia
 */
public record HologramaMeta(String nome, String categoria, String descricao) {

    static final int MAX_NOME = 80;
    static final int MAX_CATEGORIA = 40;
    static final int MAX_DESCRICAO = 200;

    /** A meta que vale quando o modelo começou pelas peças e esqueceu a primeira linha. */
    static final HologramaMeta PADRAO = new HologramaMeta("Holograma", "", "");
}
