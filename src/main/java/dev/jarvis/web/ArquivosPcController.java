package dev.jarvis.web;

import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import dev.jarvis.pc.ArquivosPcService;
import jakarta.servlet.http.HttpServletRequest;

/** Upload binário autenticado pelo TokenPcFiltro. */
@RestController
@RequestMapping("/api/pc/arquivos")
public class ArquivosPcController {
    private final ArquivosPcService arquivos;
    public ArquivosPcController(ArquivosPcService arquivos) { this.arquivos = arquivos; }

    @PostMapping("/{id}")
    public ResponseEntity<?> receber(@PathVariable String id, HttpServletRequest pedido) throws IOException {
        if (!id.matches("[a-fA-F0-9-]{36}")) return ResponseEntity.badRequest().build();
        if (pedido.getContentLengthLong() > ArquivosPcService.LIMITE)
            return ResponseEntity.status(413).body(Map.of("erro", "arquivo grande demais"));
        byte[] bytes = pedido.getInputStream().readNBytes(ArquivosPcService.LIMITE + 1);
        if (bytes.length > ArquivosPcService.LIMITE)
            return ResponseEntity.status(413).body(Map.of("erro", "arquivo grande demais"));
        return arquivos.guardar(id, bytes) ? ResponseEntity.noContent().build()
                : ResponseEntity.status(409).body(Map.of("erro", "comando ausente, expirado ou upload diferente"));
    }
}
