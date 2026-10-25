package dev.jarvis.ia;

import java.util.Set;

import dev.jarvis.util.Texto;

/**
 * "Para", "cancela", "espera": a frase curta que corta o pedido em andamento.
 *
 * <p>O atalho é estreito de propósito. Ele só vale quando há pedido em andamento (quem confere é o
 * {@code TelegramPoller}), e uma frase maior é pedido novo: "cancela o lembrete das 15h" é trabalho
 * para o modelo, não um pedido de parada. Daí as duas condições — até {@link #MAX_PALAVRAS}
 * palavras, e nenhuma palavra que não seja uma raiz de parada ou um enfeite conhecido.
 *
 * <p>Não reaproveita a lista de "não" do {@code ClassificadorDeResposta}: aquela responde a uma
 * pergunta de aprovação pendente, que é outra conversa — e sem pedido em andamento é por lá que a
 * mensagem continua passando.
 */
public final class PedidoDeParada {

    /** Acima disso a frase tem objeto ("cancela o lembrete das 15h") e é pedido, não parada. */
    private static final int MAX_PALAVRAS = 3;

    private static final Set<String> RAIZES = Set.of("para", "pare", "parar", "cancela", "cancelar",
            "espera", "espere", "deixa");

    /** O que pode acompanhar a raiz sem mudar o que a frase quer dizer. */
    private static final Set<String> ENFEITES = Set.of("jarvis", "por", "favor", "ai", "isso",
            "tudo", "ja", "agora", "pra", "la", "o", "a", "e");

    private PedidoDeParada() {
    }

    public static boolean reconhece(String texto) {
        if (texto == null) {
            return false;
        }
        String normalizado = Texto.normalizar(texto).replaceAll("[\\p{Punct}]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalizado.isEmpty()) {
            return false;
        }
        String[] palavras = normalizado.split(" ");
        if (palavras.length > MAX_PALAVRAS) {
            return false;
        }
        boolean temRaiz = false;
        for (String palavra : palavras) {
            if (RAIZES.contains(palavra)) {
                temRaiz = true;
            } else if (!ENFEITES.contains(palavra)) {
                return false;
            }
        }
        return temRaiz;
    }
}
