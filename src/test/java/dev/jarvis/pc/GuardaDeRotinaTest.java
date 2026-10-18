package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class GuardaDeRotinaTest {
    @Test void exigeNovaMensagemLiteralDoUsuario() {
        GuardaDeRotina guarda = new GuardaDeRotina();
        String pedido = "analise o projeto de demonstração";
        String codigo = guarda.pedir("analise_claude", pedido);
        assertNull(guarda.consumir("analise_claude", pedido),
                "o modelo não pode confirmar no turno que recebeu o código");
        codigo = guarda.pedir("analise_claude", pedido);
        assertNull(guarda.consumir("outra_rotina", "confirmo rotina " + codigo));
        codigo = guarda.pedir("analise_claude", pedido);
        assertEquals(pedido, guarda.consumir("analise_claude", "confirmo rotina " + codigo));
        assertNull(guarda.consumir("analise_claude", "confirmo rotina " + codigo));
    }
}
