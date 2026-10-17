package dev.jarvis.conversa;

/** Nenhum ouvinte é obrigatório; falhas de observação não interrompem a execução. */
public interface OuvinteProgresso {
    default String idPedido() { return null; }
    /** Identidade conferida na borda de entrada; zero significa sem autorização de memória. */
    default long usuarioAutenticado() { return 0; }
    /** Texto recebido diretamente do usuário, nunca produzido pelo modelo ou por uma ferramenta. */
    default String pedidoOriginal() { return ""; }
    /** Exclusão concluída neste pedido; o turno não deve permanecer no histórico. */
    default boolean memoriaEsquecida() { return false; }
    default void marcarMemoriaEsquecida() { }
    default void marcarMemoriaEsquecida(String id, long revisao) { marcarMemoriaEsquecida(); }
    default String idMemoriaEsquecida() { return ""; }
    default long revisaoMemoriaEsquecida() { return 0; }
    /** Reserva parte do orçamento de memória inserido neste turno. */
    default boolean consumirContexto(int caracteres) { return false; }
    /** Só saídas literais de ferramentas locais escolhidas pelo backend podem atestar observações. */
    default boolean evidenciaVerificada(String conteudo) { return false; }
    default String origemDaEvidencia(String conteudo) { return ""; }
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
