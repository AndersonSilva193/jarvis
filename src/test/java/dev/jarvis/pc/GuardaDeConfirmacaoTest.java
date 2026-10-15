package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.pc.GuardaDeConfirmacao.Veredito;

/** O código de 4 dígitos das ações irreversíveis e as quatro travas que o sustentam. */
class GuardaDeConfirmacaoTest {

    private final RelogioFalso relogio = new RelogioFalso();
    private final GuardaDeConfirmacao guarda = new GuardaDeConfirmacao(relogio);

    @Test
    @DisplayName("o código tem 4 dígitos e é sorteado de novo a cada pedido")
    void codigoSorteado() {
        Set<String> vistos = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String codigo = guarda.pedir("desligar", "");
            assertTrue(codigo.matches("\\d{4}"), "código fora do formato: " + codigo);
            vistos.add(codigo);
        }
        assertTrue(vistos.size() > 1, "código previsível não protege de nada");
    }

    @Test
    @DisplayName("código certo confirma uma vez só")
    void usoUnico() {
        String codigo = guarda.pedir("desligar", "");

        assertEquals(Veredito.CONFIRMADA, guarda.conferir("desligar", "", codigo));
        assertEquals(Veredito.INEXISTENTE, guarda.conferir("desligar", "", codigo),
                "o mesmo código não pode desligar duas vezes");
    }

    @Test
    @DisplayName("código vence em 2 minutos")
    void expira() {
        String codigo = guarda.pedir("reiniciar", "");
        relogio.avancar(Duration.ofSeconds(121));

        assertEquals(Veredito.EXPIRADA, guarda.conferir("reiniciar", "", codigo));
    }

    @Test
    @DisplayName("código de uma ação não confirma outra")
    void amarradoAAcao() {
        String codigo = guarda.pedir("desligar", "");

        assertEquals(Veredito.INCORRETA, guarda.conferir("reiniciar", "", codigo),
                "um código de desligar jamais pode reiniciar");
    }

    @Test
    @DisplayName("pedido novo invalida o código anterior")
    void pedidoNovoInvalidaAnterior() {
        String primeiro = guarda.pedir("desligar", "");
        String segundo = guarda.pedir("reiniciar", "");
        assertNotEquals(primeiro, segundo, "sorteio repetido 50 vezes seria outro teste");

        assertEquals(Veredito.INCORRETA, guarda.conferir("reiniciar", "", primeiro));
    }

    @Test
    @DisplayName("tentativa errada queima a confirmação")
    void erroQueimaAConfirmacao() {
        // É esta trava que torna 10.000 possibilidades suficientes: não existe segunda tentativa.
        String codigo = guarda.pedir("desligar", "");
        String errado = codigo.equals("0000") ? "1111" : "0000";

        assertEquals(Veredito.INCORRETA, guarda.conferir("desligar", "", errado));
        assertEquals(Veredito.INEXISTENTE, guarda.conferir("desligar", "", codigo),
                "depois de errar, nem o código certo vale");
    }

    @Test
    @DisplayName("cancelar esquece a confirmação em aberto")
    void cancelar() {
        String codigo = guarda.pedir("desligar", "");
        guarda.cancelar();

        assertEquals(Veredito.INEXISTENTE, guarda.conferir("desligar", "", codigo));
    }

    @Test
    @DisplayName("conferir sem nada pedido é INEXISTENTE, não confirmação")
    void semPendencia() {
        assertEquals(Veredito.INEXISTENTE, guarda.conferir("desligar", "", "1234"));
    }
}
