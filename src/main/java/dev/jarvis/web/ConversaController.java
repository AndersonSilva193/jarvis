package dev.jarvis.web;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import dev.jarvis.conversa.ConversaService;
import dev.jarvis.conversa.RegistroConversas.OcupadaException;

@RestController
@RequestMapping("/api/conversa")
public class ConversaController {
    public record Pedido(String texto) { }
    private final ConversaService conversa;
    public ConversaController(ConversaService conversa) { this.conversa = conversa; }

    @PostMapping
    public ResponseEntity<?> enviar(@RequestBody Pedido pedido) {
        if (pedido.texto() == null || pedido.texto().isBlank() || pedido.texto().length() > 8000)
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe um texto entre 1 e 8.000 caracteres."));
        return ResponseEntity.accepted().body(Map.of("id", conversa.enviar(pedido.texto().trim())));
    }
    @GetMapping("/ultima")
    public ResponseEntity<ConversaDto.Resposta> ultima() {
        var ultima = conversa.historico().ultima();
        return ultima == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(ConversaDto.de(ultima));
    }
    @GetMapping("/historico")
    public ConversaDto.Historico historico() { return ConversaDto.de(conversa.historico()); }
    @PostMapping("/limpar")
    public ResponseEntity<Void> limpar() {
        conversa.limpar();
        return ResponseEntity.noContent().build();
    }
    @ExceptionHandler(OcupadaException.class)
    public ResponseEntity<?> ocupada() {
        return ResponseEntity.status(409).body(Map.of("erro", "Aguarde o pedido atual terminar."));
    }
}
