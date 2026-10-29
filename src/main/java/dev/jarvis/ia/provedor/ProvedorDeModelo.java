package dev.jarvis.ia.provedor;

import java.io.IOException;
import java.util.function.Consumer;
import java.util.function.Predicate;

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

    /**
     * O mesmo, dizendo o que conta como resposta usável. Quem tem mais de um modelo por trás (o
     * {@link ProvedorDeApoio}) usa o predicado para decidir se passa ao seguinte; um provedor só
     * não tem para onde passar e ignora o predicado. Quem chama continua conferindo o que volta.
     */
    default RespostaDoModelo responder(PedidoAoModelo pedido, Predicate<RespostaDoModelo> aceita)
            throws IOException, InterruptedException {
        return responder(pedido);
    }

    /**
     * O mesmo pedido, entregando o texto conforme o modelo o escreve. Só texto: ferramenta,
     * pensamento e busca não passam por {@code aoTexto}, mas voltam na resposta, como sempre.
     *
     * <p>O padrão não transmite — responde inteiro e entrega o texto num pedaço só. É o que vale
     * para quem não sabe transmitir, e mantém essa gente compilando sem mexer em nada. Quem sabe
     * (a Anthropic e o local) sobrescreve.
     *
     * <p>{@code cancelamento} corta de fora: quem implementa pendura nele o que fecha o stream, e
     * depois do corte a chamada termina com o erro que o corte provocou — quem chama confere
     * {@link Cancelamento#cancelado()} antes de tratar esse erro como falha do modelo.
     */
    default RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto,
            Cancelamento cancelamento) throws IOException, InterruptedException {
        RespostaDoModelo resposta = responder(pedido);
        if (!cancelamento.cancelado()) {
            String texto = String.join("", resposta.textos());
            if (!texto.isEmpty()) {
                aoTexto.accept(texto);
            }
        }
        return resposta;
    }
}
