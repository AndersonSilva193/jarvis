package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O atalho é estreito: frase curta de parada sim, pedido com objeto não. */
class PedidoDeParadaTest {

    @Test
    @DisplayName("as frases curtas de parada são reconhecidas, com acento, pontuação ou 'jarvis' na frente")
    void reconheceOsPedidosDeParada() {
        for (String frase : new String[] { "para", "Para!", "pare", "parar", "cancela", "cancelar",
                "espera", "espere", "deixa", "Jarvis, para", "para por favor", "espera aí",
                "deixa pra lá", "cancela isso", "PARA JÁ", "  pare.  " }) {
            assertTrue(PedidoDeParada.reconhece(frase), frase);
        }
    }

    @Test
    @DisplayName("pedido com objeto não é parada: ele tem de chegar ao modelo")
    void naoConfundePedidoComParada() {
        for (String frase : new String[] { "cancela o lembrete das 15h", "para onde vamos amanhã",
                "pare de me lembrar disso", "espera o relatório ficar pronto", "deixa o volume alto",
                "cancela lembrete", "para o servidor", "sim", "", "   ", "obrigado" }) {
            assertFalse(PedidoDeParada.reconhece(frase), frase);
        }
    }

    @Test void textoNuloNaoReconhece() {
        assertFalse(PedidoDeParada.reconhece(null));
    }
}
