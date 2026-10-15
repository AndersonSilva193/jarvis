package dev.jarvis.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.tela.TelaEstadoService;

/**
 * Por onde a HUD conta ao servidor o que está aberto na tela.
 *
 * Sem isto o Jarvis fica cego: o {@code controlar_tela} não acha alvo nenhum e a linha
 * "Na tela agora" não aparece no prompt. A HUD reposta a cada mudança — abrir, fechar, minimizar,
 * fixar, focar, tocar ou pausar.
 *
 * O token da HUD é exigido pelo TokenHudFiltro, que já protege tudo sob /api menos /api/saude.
 */
@RestController
@RequestMapping("/api/tela")
public class TelaController {

    private final TelaEstadoService tela;

    public TelaController(TelaEstadoService tela) {
        this.tela = tela;
    }

    @PostMapping("/estado")
    public ResponseEntity<?> estado(@RequestBody TelaEstadoDto pedido) {
        if (pedido == null) {
            return ResponseEntity.badRequest().body(Map.of("erro", "Informe o estado da tela."));
        }
        List<String> erros = pedido.erros();
        if (!erros.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("erro", String.join("; ", erros)));
        }
        tela.guardar(pedido.paraDominio(Instant.now().toString()));
        return ResponseEntity.noContent().build();
    }
}
