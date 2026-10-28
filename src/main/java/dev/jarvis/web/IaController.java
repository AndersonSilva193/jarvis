package dev.jarvis.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.ModoDeIaEvento;
import dev.jarvis.ia.provedor.SeletorDeModelo;

/**
 * O modo de IA para a HUD: o que está valendo e a troca pelo botão (ou pela voz, que a HUD
 * transforma num POST). Fica atrás do {@code HUD_TOKEN} como todo o resto de {@code /api}.
 *
 * <p>A resposta tem a forma do evento {@code ia.modo}, de propósito: a HUD desenha os dois com o
 * mesmo código.
 */
@RestController
@RequestMapping("/api/ia")
public class IaController {

    private final SeletorDeModelo seletor;

    public IaController(SeletorDeModelo seletor) {
        this.seletor = seletor;
    }

    /** {@code {"modo": "local"}}. */
    public record Pedido(String modo) {
    }

    @GetMapping("/modo")
    public ModoDeIaEvento modo() {
        return seletor.retrato().evento();
    }

    /** Modo desconhecido é 400: um botão mandando lixo é bug da HUD, não pedido do dono. */
    @PostMapping("/modo")
    public ResponseEntity<ModoDeIaEvento> trocar(@RequestBody(required = false) Pedido pedido) {
        var modo = pedido == null ? java.util.Optional.<ModoDeIa>empty()
                : ModoDeIa.deChave(pedido.modo());
        if (modo.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        seletor.mudar(modo.get(), "hud");
        return ResponseEntity.ok(seletor.retrato().evento());
    }
}
