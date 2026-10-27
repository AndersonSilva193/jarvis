package dev.jarvis.conversa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A fila em si: teto por chat e drenagem. O outro lado — o item virando turno no histórico —
 * é o {@code ResultadoTardioNoHistoricoTest}, que mora em {@code dev.jarvis.ia} porque monta o
 * {@code Assistente}. Este fica aqui no mesmo pacote da classe para poder ler o
 * {@link ResultadosTardios#MAX_POR_CHAT}, que é package-private como as outras constantes de
 * teto do projeto.
 */
class ResultadosTardiosTest {

    private final ResultadosTardios tardios = new ResultadosTardios();

    @Test void filaTemTetoPorChat() {
        for (int i = 0; i < ResultadosTardios.MAX_POR_CHAT + 3; i++) tardios.registrar(7, "t", "r" + i);
        var itens = tardios.drenar(7);
        assertEquals(ResultadosTardios.MAX_POR_CHAT, itens.size());
        assertEquals("r" + (ResultadosTardios.MAX_POR_CHAT + 2), itens.getLast().texto(), "sai o mais velho");
    }

    @Test void drenarEsvazia() {
        tardios.registrar(7, "t", "r");
        assertEquals(1, tardios.drenar(7).size());
        assertTrue(tardios.drenar(7).isEmpty(), "o item sai uma vez só");
    }

    @Test void ignoraTextoVazioEChatInvalido() {
        tardios.registrar(7, "t", "   ");
        tardios.registrar(7, "t", null);
        tardios.registrar(0, "t", "r");
        assertTrue(tardios.drenar(7).isEmpty());
        assertTrue(tardios.drenar(0).isEmpty());
    }
}
