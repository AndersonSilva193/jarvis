package dev.jarvis.ia;

import java.io.IOException;

/** Erro HTTP do provedor com detalhe curto e sem credenciais para a HUD. */
public final class ErroDaApiDoModelo extends IOException {
    private final int status;
    private final String detalhe;

    public ErroDaApiDoModelo(int status, String detalhe) {
        super("API do Claude respondeu HTTP " + status + ": " + detalhe);
        this.status = status;
        this.detalhe = detalhe;
    }

    public int status() { return status; }
    public String detalhe() { return detalhe; }
}
