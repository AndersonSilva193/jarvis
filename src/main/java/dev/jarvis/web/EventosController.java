package dev.jarvis.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class EventosController {
    private final EventosHud eventos;
    public EventosController(EventosHud eventos) { this.eventos = eventos; }
    @GetMapping("/api/eventos")
    public SseEmitter eventos() { return eventos.registrar(); }
}
