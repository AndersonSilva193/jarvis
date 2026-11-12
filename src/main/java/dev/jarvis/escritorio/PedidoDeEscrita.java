package dev.jarvis.escritorio;

import tools.jackson.databind.node.ObjectNode;

/**
 * Uma escrita numa planilha dos agentes, na pasta do Jarvis no PC: a ação de planilha do agente,
 * o arquivo e os dados que ela leva.
 *
 * @param id         o id do pedido na fila (não o do comando: cada envio ao PC é um comando novo)
 * @param criadoEm   ISO-8601, em texto pelo mesmo motivo do {@code ComandoPc}
 * @param tentativas quantas vezes já foi ao PC sem resposta boa
 */
public record PedidoDeEscrita(String id, String acao, String arquivo, ObjectNode dados, String criadoEm,
        int tentativas) {

    public PedidoDeEscrita(String id, String acao, String arquivo, ObjectNode dados, String criadoEm) {
        this(id, acao, arquivo, dados, criadoEm, 0);
    }

    PedidoDeEscrita maisUmaTentativa() {
        return new PedidoDeEscrita(id, acao, arquivo, dados, criadoEm, tentativas + 1);
    }
}
