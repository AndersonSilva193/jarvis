package dev.jarvis.busca;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * As URLs que a {@code buscar_na_web} devolveu no pedido corrente — as únicas que a
 * {@code ler_pagina_web} aceita.
 *
 * <p>É o que impede o modelo de mandar ler um endereço arbitrário: ele só lê o que uma busca trouxe
 * neste mesmo pedido. Um par de campos, e não um mapa por pedido, pelo motivo do orçamento da
 * lixeira: o {@code Assistente} segura o lock pelo turno inteiro, então só um pedido corre por vez,
 * e um mapa precisaria de evicção para nada. Pedido novo apaga as URLs do anterior.
 */
@Component
public class UrlsDaBusca {

    private String pedido;
    private final Set<String> urls = new HashSet<>();

    /** Acrescenta as URLs ao pedido; pedido sem identificador não guarda nada. */
    public synchronized void registrar(String idPedido, List<String> novas) {
        if (idPedido == null || idPedido.isBlank()) {
            return;
        }
        if (!idPedido.equals(pedido)) {
            pedido = idPedido;
            urls.clear();
        }
        urls.addAll(novas);
    }

    /** Se a URL apareceu numa busca deste pedido. Pedido sem identificador nunca casa. */
    public synchronized boolean apareceu(String idPedido, String url) {
        return idPedido != null && !idPedido.isBlank() && idPedido.equals(pedido)
                && url != null && urls.contains(url.strip());
    }
}
