package dev.jarvis.ia.provedor;

import java.util.Optional;

import dev.jarvis.util.Texto;

/**
 * O que o dono escolheu para pensar. A constante é Java; a {@link #chave()} é o que vai ao
 * {@code modo-ia.json}, à API da HUD e ao evento {@code ia.modo} — o par de sempre, como na
 * {@code Origem}.
 */
public enum ModoDeIa {

    /** O local quando o PC pode atender, o Claude quando não. Ninguém é avisado da troca. */
    AUTO("auto", "automático"),
    /** O local sempre que der. Caindo no Claude, o dono é avisado uma vez por período. */
    LOCAL("local", "local"),
    /** Sempre o Claude. */
    CLAUDE("claude", "Claude");

    private final String chave;
    private final String rotulo;

    ModoDeIa(String chave, String rotulo) {
        this.chave = chave;
        this.rotulo = rotulo;
    }

    public String chave() {
        return chave;
    }

    /** O nome falado: "Modo automático.". */
    public String rotulo() {
        return rotulo;
    }

    /** O modo daquela chave, sem diferenciar maiúscula nem acento; vazio para o resto. */
    public static Optional<ModoDeIa> deChave(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return Optional.empty();
        }
        String procurada = Texto.normalizar(bruto);
        for (ModoDeIa modo : values()) {
            if (modo.chave.equals(procurada) || Texto.normalizar(modo.rotulo).equals(procurada)) {
                return Optional.of(modo);
            }
        }
        return Optional.empty();
    }
}
