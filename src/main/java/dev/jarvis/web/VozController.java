package dev.jarvis.web;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.voz.VozService;

import java.util.concurrent.TimeUnit;

/**
 * A voz da HUD.
 *
 * A HUD pergunta antes de cada fala: se vier uma URL, ela toca o MP3; se vier "navegador", ela
 * fala sozinha com a voz do sistema. Por isso quase nada aqui devolve erro — um 500 deixaria a
 * tela muda, e o combinado é o contrário.
 */
@RestController
@RequestMapping("/api/voz")
public class VozController {

    private final VozService voz;

    public VozController(VozService voz) {
        this.voz = voz;
    }

    /** Texto de entrada da HUD. */
    public record Pedido(String texto) {
    }

    @PostMapping("/sintetizar")
    public ResponseEntity<VozDto.Fala> sintetizar(@RequestBody Pedido pedido) {
        if (pedido == null || pedido.texto() == null || pedido.texto().isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(VozDto.de(voz.preparar(pedido.texto())));
    }

    /**
     * O MP3 em si. O id é o sha-256 do texto nesta voz, então o conteúdo nunca muda: dá para o
     * navegador guardar por um dia e nem pedir de novo.
     */
    @GetMapping("/audio/{id}")
    public ResponseEntity<byte[]> audio(@PathVariable String id) {
        return voz.audio(id)
                .map(bytes -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                        .header("X-Content-Type-Options", "nosniff")
                        .contentType(MediaType.valueOf("audio/mpeg"))
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/status")
    public VozService.Status status() {
        return voz.status();
    }
}
