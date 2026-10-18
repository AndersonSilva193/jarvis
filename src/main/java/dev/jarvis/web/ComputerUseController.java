package dev.jarvis.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;

/** Consulta e parada da HUD; autenticacao Bearer e aplicada pelo TokenHudFiltro. */
@RestController
@RequestMapping("/api/computer")
public class ComputerUseController {
    private final TarefasComputerUse tarefas;
    private final long usuario;
    public ComputerUseController(TarefasComputerUse tarefas, JarvisProperties props) {
        this.tarefas = tarefas; this.usuario = props.telegram().usuarioPermitido();
    }
    @GetMapping("/ultima")
    public ResponseEntity<TarefasComputerUse.Retrato> ultima() {
        var estado = tarefas.ultima(usuario);
        return estado == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(estado);
    }
    @GetMapping("/{id}")
    public ResponseEntity<TarefasComputerUse.Retrato> estado(@PathVariable String id) {
        var estado = tarefas.estado(id, usuario);
        return estado == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(estado);
    }
    @PostMapping("/{id}/cancelar")
    public ResponseEntity<Void> cancelar(@PathVariable String id) {
        return tarefas.cancelar(id, usuario) ? ResponseEntity.accepted().build()
                : ResponseEntity.notFound().build();
    }
}
