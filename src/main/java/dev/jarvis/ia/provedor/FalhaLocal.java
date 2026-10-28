package dev.jarvis.ia.provedor;

import java.io.IOException;

/**
 * O modelo local não respondeu, ou respondeu o que não dá para usar.
 *
 * <p>É uma {@link IOException} porque quem chama um {@link ProvedorDeModelo} já trata essa, e o
 * modelo local fica atrás de uma ida e volta de rede. O {@link #motivo()} é um código estável —
 * {@code timeout}, {@code sem_memoria}, {@code erro_de_formato}... — e é ele que vira o motivo da
 * reserva no Claude, contado por dia no {@code MedidorDeCusto}.
 */
public class FalhaLocal extends IOException {

    private final String motivo;

    public FalhaLocal(String motivo, String mensagem) {
        super(mensagem);
        this.motivo = motivo;
    }

    public String motivo() {
        return motivo;
    }
}
