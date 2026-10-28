package dev.jarvis.ia.provedor;

import java.io.IOException;

/**
 * Como conversar com um modelo, sem dizer qual.
 *
 * <p>Todo chamador do Jarvis fala com esta interface, nunca com o cliente HTTP de um provedor: o
 * pedido e a resposta estão no formato neutro de {@link Bloco}, e quem traduz é a implementação.
 * Quem cuida de cache, esforço e custo também é ela — o chamador só diz de onde vem
 * ({@link OpcoesDoPedido#origem()}) e, quando a origem não basta, quanto pensar.
 *
 * <p>Os erros atravessam como estão: {@code IOException} de rede ou de HTTP, e
 * {@code InterruptedException} quando quem chamou desistiu.
 */
public interface ProvedorDeModelo {

    RespostaDoModelo responder(PedidoAoModelo pedido) throws IOException, InterruptedException;
}
