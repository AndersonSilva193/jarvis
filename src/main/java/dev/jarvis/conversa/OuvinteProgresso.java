package dev.jarvis.conversa;

/** Nenhum ouvinte é obrigatório; falhas de observação não interrompem a execução. */
public interface OuvinteProgresso {
    default String idPedido() { return null; }
    /** De onde veio o pedido (telegram, hud, briefing, tarefa). É como a ferramenta sabe o canal. */
    default String canal() { return ""; }
    /** Houve busca web real antes desta chamada de ferramenta no mesmo pedido. */
    default boolean pesquisaFeita() { return false; }
    /** ID do tool_use que originou esta execução, para idempotência de efeitos externos. */
    default String idFerramenta() { return ""; }
    default void iniciado() { }
    default void ferramenta(String nome, String descricao, Integer passo, Integer total) { }

    static void avisar(Runnable aviso) {
        try { aviso.run(); } catch (RuntimeException ignorada) {
            // O resultado da tarefa não depende de uma tela continuar conectada.
        }
    }
}
