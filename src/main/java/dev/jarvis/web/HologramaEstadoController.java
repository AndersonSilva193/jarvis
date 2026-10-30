package dev.jarvis.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.holograma.EstadoDoHologramaService;

/**
 * Por onde a HUD conta ao servidor como está o holograma: aberto, o objeto, as peças, a explosão, a
 * seleção. É o que faz o Jarvis entender "explode mais" e "o que é essa peça?".
 *
 * <p>Separado do {@code /api/tela/estado} de propósito: o corpo é outro (até 250 peças), a HUD o
 * manda com outra cadência (300 ms), e o retrato das janelas continua igual para a HUD velha.
 */
@RestController
public class HologramaEstadoController {

    private final EstadoDoHologramaService estado;

    public HologramaEstadoController(EstadoDoHologramaService estado) {
        this.estado = estado;
    }

    @PostMapping("/api/holograma/estado")
    public ResponseEntity<?> guardar(@RequestBody(required = false) EstadoDoHologramaDto pedido) {
        if (pedido == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe o estado do holograma."));
        }
        List<String> erros = pedido.erros();
        if (!erros.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("erro", String.join("; ", erros)));
        }
        estado.guardar(pedido.paraDominio(Instant.now().toString()));
        return ResponseEntity.noContent().build();
    }
}
