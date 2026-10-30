package dev.jarvis.holograma;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.jarvis.util.Texto;

/**
 * "Explode", "monta", "mais", "vista de cima": o comando curto que não precisa de modelo.
 *
 * <p>Com o modo Holograma aberto, a fala mais comum é uma palavra só, e mandá-la ao modelo custaria
 * um ou dois segundos e uma chamada paga para decidir o óbvio. O atalho é estreito como o do
 * {@code PedidoDeParada}: a frase inteira tem de casar com um dos padrões, tirados os enfeites
 * ("Jarvis", "por favor", "agora"). Qualquer palavra a mais — "explode só os pistões" — e a frase é
 * do modelo, que sabe resolver alvo.
 *
 * <p>Quem confere se o modo está aberto e se não há uma pergunta pendente ("para" pode ser o "não" de
 * um "Posso?") é quem chama. Aqui é só texto, e puro, para o teste.
 */
public final class IntentoDoHolograma {

    /** O que a ferramenta {@code holograma} recebe: a ação e o {@code valor}, quando há. */
    public record Intento(String acao, String valor) { }

    /** Acima disso, tirados os enfeites, a frase tem objeto e é pedido para o modelo. */
    private static final int MAX_PALAVRAS = 4;

    private static final Set<String> ENFEITES = Set.of("jarvis", "por", "favor", "ai", "agora",
            "ja", "pra", "mim", "isso", "ele", "ela", "tudo", "de", "novo", "e", "o", "a", "um", "pouco");

    private record Padrao(Pattern regex, java.util.function.Function<Matcher, Intento> intento) { }

    private static Padrao padrao(String regex, java.util.function.Function<Matcher, Intento> intento) {
        return new Padrao(Pattern.compile("^(?:" + regex + ")$"), intento);
    }

    private static final List<Padrao> PADROES = List.of(
            padrao("(?:explode|explodir|explodi|expande|expandir|separa|separar) mais|mais",
                    m -> new Intento("explodir", "mais")),
            padrao("(?:explode|explodir|expande|separa|junta|monta) menos|menos",
                    m -> new Intento("explodir", "menos")),
            padrao("explode|explodir|explodi|expande|expandir|separa|separar|desmonta|desmontar",
                    m -> new Intento("explodir", "1")),
            padrao("monta|montar|junta|juntar|remonta|remontar|volta (?:as|com as) pecas",
                    m -> new Intento("montar", "")),
            padrao("gira|girar|roda|rodar|comeca (?:a )?girar",
                    m -> new Intento("girar_continuo", "")),
            padrao("para|pare|parar|para (?:de )?girar|para (?:de )?rodar|fica parado",
                    m -> new Intento("parar", "")),
            padrao("aproxima|aproximar|chega (?:mais )?perto|zoom|amplia|ampliar",
                    m -> new Intento("zoom", "aproximar")),
            padrao("afasta|afastar|diminui|diminuir",
                    m -> new Intento("zoom", "afastar")),
            padrao("reseta|resetar|reinicia|reiniciar|centraliza|centralizar|reseta (?:a )?(?:vista|camera)"
                    + "|reinicia (?:a )?(?:vista|camera)|volta (?:ao|pro) (?:inicio|comeco)",
                    m -> new Intento("resetar", "")),
            padrao("(?:vista|visao|olha|mostra|ve)? ?(?:de|da|pela|por|do)? ?(cima|frente|lado)",
                    m -> new Intento("vista", m.group(1))),
            padrao("(?:vista|visao)? ?isometrica",
                    m -> new Intento("vista", "isometrica")),
            padrao("fecha|fechar|fecha (?:o )?holograma|fechar (?:o )?holograma|sai do holograma"
                    + "|sair do holograma",
                    m -> new Intento("fechar", "")));

    private IntentoDoHolograma() {
    }

    public static Optional<Intento> reconhecer(String texto) {
        if (texto == null) return Optional.empty();
        String normalizado = Texto.normalizar(texto).replaceAll("[\\p{Punct}…]+", " ")
                .replaceAll("\\s+", " ").trim();
        if (normalizado.isEmpty()) return Optional.empty();
        String[] palavras = normalizado.split(" ");
        // Os enfeites saem das pontas, não do meio: "vista de cima" precisa do "de".
        int inicio = 0;
        int fim = palavras.length;
        while (inicio < fim && ENFEITES.contains(palavras[inicio])) inicio++;
        while (fim > inicio && ENFEITES.contains(palavras[fim - 1])) fim--;
        if (inicio == fim || fim - inicio > MAX_PALAVRAS) return Optional.empty();
        String miolo = String.join(" ", java.util.Arrays.copyOfRange(palavras, inicio, fim));
        for (Padrao padrao : PADROES) {
            Matcher m = padrao.regex().matcher(miolo);
            if (m.matches()) return Optional.of(padrao.intento().apply(m));
        }
        return Optional.empty();
    }
}
