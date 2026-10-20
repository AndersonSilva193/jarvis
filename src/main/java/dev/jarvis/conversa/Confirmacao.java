package dev.jarvis.conversa;

/**
 * A aprovação pendente como a HUD a vê.
 *
 * <p>A {@code pergunta} é o texto em linguagem natural que o usuário responde, montado em Java pela
 * ferramenta a partir de dado real — nunca escrito pelo modelo. O construtor de três argumentos
 * existe para quem ainda não tem pergunta própria e repete a descrição.
 */
public record Confirmacao(String codigo, String descricao, String expiraEm, String pergunta) {
    public Confirmacao(String codigo, String descricao, String expiraEm) {
        this(codigo, descricao, expiraEm, descricao);
    }
}
