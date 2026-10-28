package dev.jarvis.ia.provedor;

import java.util.List;

/** Uma chamada ao modelo, no formato que qualquer provedor sabe ler. */
public record PedidoAoModelo(InstrucoesDeSistema sistema, List<Mensagem> mensagens,
        ListaDeFerramentas ferramentas, OpcoesDoPedido opcoes) {

    public PedidoAoModelo {
        mensagens = List.copyOf(mensagens);
        ferramentas = ferramentas == null ? ListaDeFerramentas.vazia() : ferramentas;
        sistema = sistema == null ? new InstrucoesDeSistema(List.of()) : sistema;
    }
}
