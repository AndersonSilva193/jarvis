package dev.jarvis.financas;

import dev.jarvis.escritorio.EscritaNoPc;

/**
 * Um lançamento aceito e como ele foi para o {@code financas.csv} do PC. Fora de
 * {@link EscritaNoPc.Estado#GRAVADO}, ele já conta nas contas e está na fila do PC.
 */
public record Registrado(Lancamento lancamento, EscritaNoPc.Estado noPc) {

    public boolean gravadoNoPc() {
        return noPc == EscritaNoPc.Estado.GRAVADO;
    }
}
