package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.jarvis.ia.TrocaDeModelo.Comando;
import dev.jarvis.ia.TrocaDeModelo.Consultar;
import dev.jarvis.ia.TrocaDeModelo.Mudar;
import dev.jarvis.ia.provedor.ModoDeIa;

/** As frases que o servidor atende sozinho, e as parecidas que continuam indo ao modelo. */
class TrocaDeModeloTest {

    @Test void trocasComAcentoEPontuacao() {
        assertEquals(mudar(ModoDeIa.LOCAL), TrocaDeModelo.comando("usa o modelo local"));
        assertEquals(mudar(ModoDeIa.LOCAL), TrocaDeModelo.comando("MODO LOCAL"));
        assertEquals(mudar(ModoDeIa.LOCAL), TrocaDeModelo.comando("Usa o modelo local, por favor."));
        assertEquals(mudar(ModoDeIa.LOCAL), TrocaDeModelo.comando("Jarvis, muda pro modo local!"));
        assertEquals(mudar(ModoDeIa.CLAUDE), TrocaDeModelo.comando("Usa o Claude."));
        assertEquals(mudar(ModoDeIa.CLAUDE), TrocaDeModelo.comando("Jarvis, modo Claude"));
        assertEquals(mudar(ModoDeIa.AUTO), TrocaDeModelo.comando("Modo automático!"));
        assertEquals(mudar(ModoDeIa.AUTO), TrocaDeModelo.comando("  volta pro modo AUTOMÁTICO  "));
    }

    @Test void perguntaSobreOModelo() {
        for (String texto : List.of("Qual modelo você está usando?", "qual modelo vc tá usando?",
                "Jarvis, qual o modo agora?", "Que modelo você está usando?"))
            assertEquals(Optional.of(new Consultar()), TrocaDeModelo.comando(texto), texto);
    }

    @Test void frasesComObjetoContinuamIndoAoModelo() {
        for (String texto : List.of("usa o modelo local para resumir o texto de ontem",
                "modo local e claude", "o modelo local é bom?", "local", "claude",
                "qual modelo de celular comprar?", "usa o Claude pra isso: resume", "", "?!"))
            assertTrue(TrocaDeModelo.comando(texto).isEmpty(), texto);
    }

    @Test void pontualTiraOPrefixoEDevolveOPedidoIntacto() {
        assertEquals(Optional.of("quanto é 2+2?"), TrocaDeModelo.pontual("Pergunta pro Claude: quanto é 2+2?"));
        assertEquals(Optional.of("resume a notícia"),
                TrocaDeModelo.pontual("usa o Claude pra isso: resume a notícia"));
        assertEquals(Optional.of("Escreve um poema"),
                TrocaDeModelo.pontual("Jarvis, pergunte ao Claude, Escreve um poema"));
        assertEquals(Optional.of("linha 1\nlinha 2"),
                TrocaDeModelo.pontual("pergunta para o claude: linha 1\nlinha 2"));
    }

    @Test void pontualSemPedidoOuSemSeparadorNaoVale() {
        for (String texto : List.of("pergunta pro Claude:", "pergunta pro Claude se vai chover",
                "o Claude: oi", "usa o Claude"))
            assertTrue(TrocaDeModelo.pontual(texto).isEmpty(), texto);
    }

    private static Optional<Comando> mudar(ModoDeIa modo) {
        return Optional.of(new Mudar(modo));
    }
}
