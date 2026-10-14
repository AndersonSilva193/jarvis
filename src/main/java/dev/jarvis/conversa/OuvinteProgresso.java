package dev.jarvis.conversa;

/** Nenhum ouvinte é obrigatório; falhas de observação não interrompem a execução. */
public interface OuvinteProgresso {
    default String idPedido() { return null; }
    default void iniciado() { }
    default void ferramenta(String nome, String descricao, Integer passo, Integer total) { }

    static void avisar(Runnable aviso) {
        try { aviso.run(); } catch (RuntimeException ignorada) {
            // O resultado da tarefa não depende de uma tela continuar conectada.
        }
    }
}
