package dev.jarvis.web;

import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.holograma.controle.BilhetesDoCanal;

/**
 * O bilhete que a HUD troca pelo {@code HUD_TOKEN} antes de abrir o {@code /ws/holograma}. O token
 * de verdade é exigido aqui pelo {@link TokenHudFiltro}, em {@code Authorization: Bearer}, e nunca
 * vai na URL do WebSocket.
 */
@RestController
public class CanalDoHologramaController {

    private final BilhetesDoCanal bilhetes;

    public CanalDoHologramaController(BilhetesDoCanal bilhetes) {
        this.bilhetes = bilhetes;
    }

    @PostMapping("/api/holograma/controle/bilhete")
    public ResponseEntity<Map<String, Object>> bilhete() {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Map.of("bilhete", bilhetes.emitir(), "validade", BilhetesDoCanal.VALIDADE.toSeconds()));
    }
}
