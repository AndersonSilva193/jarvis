package dev.jarvis.telegram;

import java.io.IOException;

/**
 * O Telegram respondeu, mas recusou a chamada ({@code "ok": false}) — diferente de a rede ter
 * caído. A distinção importa no envio: uma recusa de formatação vale um reenvio em texto puro,
 * enquanto uma falha de rede não vale, porque arriscaria mandar a mesma mensagem duas vezes.
 */
public class TelegramRecusouException extends IOException {

    private final transient String metodo;
    private final int status;
    private final transient String descricao;

    public TelegramRecusouException(String metodo, int status, String descricao) {
        // Nunca mencionamos a URL: ela contém o token do bot.
        super("Telegram recusou " + metodo + " (HTTP " + status + "): " + descricao);
        this.metodo = metodo;
        this.status = status;
        this.descricao = descricao;
    }

    public String metodo() {
        return metodo;
    }

    public int status() {
        return status;
    }

    /** O campo {@code description} da resposta, que é onde o Telegram explica o motivo. */
    public String descricao() {
        return descricao;
    }
}
