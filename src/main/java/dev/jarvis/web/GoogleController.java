package dev.jarvis.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import dev.jarvis.google.GooglePlanilhasService;

/** O callback é público apenas para receber o código protegido pelo state de uso único. */
@RestController
@RequestMapping("/api/google")
public class GoogleController {
    private final GooglePlanilhasService google;
    public GoogleController(GooglePlanilhasService google) { this.google = google; }

    @GetMapping("/status")
    public Map<String, Boolean> status() {
        return Map.of("configurado", google.configurado(), "autorizado", google.autorizado());
    }

    @PostMapping("/oauth/iniciar")
    public ResponseEntity<?> iniciar() {
        try { return ResponseEntity.ok(Map.of("url", google.iniciar())); }
        catch (Exception e) { return ResponseEntity.status(409).body(Map.of("erro", e.getMessage())); }
    }

    @GetMapping("/oauth/callback")
    public ResponseEntity<String> callback(@RequestParam(required = false) String state,
            @RequestParam(required = false) String code) {
        try {
            google.concluir(state, code);
            return ResponseEntity.ok().header("Content-Security-Policy", "default-src 'none'")
                    .body("Google Sheets conectado. Pode fechar esta janela.");
        } catch (Exception e) {
            return ResponseEntity.badRequest().header("Content-Security-Policy", "default-src 'none'")
                    .body("Não foi possível conectar Google Sheets. Volte à HUD e tente novamente.");
        }
    }
}
