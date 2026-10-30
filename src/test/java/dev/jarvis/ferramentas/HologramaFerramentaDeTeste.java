package dev.jarvis.ferramentas;

import dev.jarvis.holograma.EstadoDoHologramaService;
import dev.jarvis.holograma.FalasDoHolograma;
import dev.jarvis.holograma.GaleriaDeHologramas;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.json.JsonMapper;

/** Monta a {@link HologramaFerramenta} sem o serviço de geração, para testes de fora do pacote. */
public final class HologramaFerramentaDeTeste {

    private HologramaFerramentaDeTeste() {
    }

    public static Ferramenta criar(PresencaDaHud hud, JsonMapper json, EstadoDoHologramaService estado,
            GaleriaDeHologramas galeria) {
        return new HologramaFerramenta(hud, json, estado, galeria, new FalasDoHolograma(),
                (objeto, detalhe, aoFim) -> "h00000000", texto -> { }, new FalasService());
    }
}
