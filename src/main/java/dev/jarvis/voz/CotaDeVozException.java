package dev.jarvis.voz;

/**
 * O provedor de voz recusou por cota, chave ou excesso de chamadas.
 *
 * É diferente de uma IOException de propósito: isto aqui faz o Jarvis trocar para a voz do
 * navegador e avisar no Telegram; a IOException só desvia aquela fala.
 */
public class CotaDeVozException extends Exception {

    private final String motivo;

    public CotaDeVozException(String motivo, String mensagem) {
        super(mensagem);
        this.motivo = motivo;
    }

    /** Resumo em uma palavra para o status e para o log: "cota", "chave" ou "limite". */
    public String motivo() {
        return motivo;
    }
}
