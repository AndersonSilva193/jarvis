package dev.jarvis.pc;

/**
 * O que o agente devolve sobre um comando.
 *
 * A {@code mensagem} vem de fora e vai parar no prompt do modelo, então o construtor a limpa:
 * sem quebra de linha, sem caractere de controle e com teto. Um agente comprometido — ou só um
 * {@code stderr} comprido do Windows — não vai injetar instrução nem comer contexto por aqui.
 *
 * @param imagem os bytes já decodificados do PNG/JPEG da captura, ou nulo. O base64 morre no DTO
 *               da camada web: nem a ferramenta nem o {@code TelegramClient} precisam saber dele.
 */
public record ResultadoPc(String comandoId, boolean ok, String mensagem, byte[] imagem, String mime,
        boolean arquivo, tools.jackson.databind.JsonNode dados) {
    public ResultadoPc(String comandoId, boolean ok, String mensagem, byte[] imagem, String mime,
            boolean arquivo) {
        this(comandoId, ok, mensagem, imagem, mime, arquivo, null);
    }
    public ResultadoPc(String comandoId, boolean ok, String mensagem, byte[] imagem, String mime) {
        this(comandoId, ok, mensagem, imagem, mime, false, null);
    }

    /** Teto da mensagem que volta ao modelo. Tudo que a ferramenta devolve vira token. */
    public static final int MAX_MENSAGEM = 300;

    public ResultadoPc {
        mensagem = mensagem == null ? "" : mensagem.replaceAll("[\\r\\n\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (mensagem.length() > MAX_MENSAGEM) {
            mensagem = mensagem.substring(0, MAX_MENSAGEM - 1) + "…";
        }
    }

    public boolean temImagem() {
        return imagem != null && imagem.length > 0;
    }

    /** O resultado de um comando que o PC nunca respondeu. */
    static ResultadoPc expirado(String comandoId) {
        return new ResultadoPc(comandoId, false, "o PC não respondeu em 60 segundos", null, null);
    }
}
