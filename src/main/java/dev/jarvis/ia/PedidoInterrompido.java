package dev.jarvis.ia;

/**
 * O corte do turno. Sem pilha nem mensagem supressa: ela não é um erro, é o usuário pedindo para
 * parar, e nunca sai deste pacote — o {@link CicloDeFerramentas} lança e o {@link Assistente} pega.
 */
final class PedidoInterrompido extends RuntimeException {

    /** A resposta de um turno cortado pelo usuário. Curta de propósito: ele pediu para parar. */
    static final String PAREI = "Parei, senhor.";

    PedidoInterrompido() {
        super(PAREI, null, false, false);
    }
}
