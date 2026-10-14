package dev.jarvis.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import dev.jarvis.conversa.AnexosService;

@RestController
@RequestMapping("/api/anexos")
public class AnexosController {
    private final AnexosService anexos;
    public AnexosController(AnexosService anexos) { this.anexos = anexos; }
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> obter(@PathVariable String id) throws IOException {
        var conteudo = anexos.obter(id);
        if (conteudo.isEmpty()) return ResponseEntity.notFound().build();
        var arquivo = conteudo.get();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(arquivo.anexo().nome(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(arquivo.anexo().mime())).body(arquivo.bytes());
    }
}
