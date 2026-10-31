package dev.jarvis.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.jarvis.holograma.BenchmarkDeHolograma;
import dev.jarvis.holograma.DetalheHolograma;

/**
 * O benchmark do holograma: {@code POST} começa (202, ou 409 se já há um rodando) e {@code GET}
 * diz onde está e devolve o último relatório. Bearer como o resto de {@code /api}; quem dispara
 * costuma ser o {@code scripts/benchmark-holograma.mjs}.
 */
@RestController
public class HologramaBenchmarkController {

    private final BenchmarkDeHolograma benchmark;

    public HologramaBenchmarkController(BenchmarkDeHolograma benchmark) {
        this.benchmark = benchmark;
    }

    /**
     * @param detalhe    {@code rapido}, {@code normal} (o padrão) ou {@code detalhado}
     * @param provedores {@code local,anthropic} (o padrão), ou um só; {@code haiku} vale como {@code anthropic}
     */
    @PostMapping("/api/holograma/benchmark")
    public ResponseEntity<BenchmarkDeHolograma.Andamento> iniciar(@RequestParam(required = false) String detalhe,
            @RequestParam(required = false) String provedores) {
        Optional<DetalheHolograma> nivel = DetalheHolograma.porChave(detalhe);
        Optional<List<String>> quais = provedores(provedores);
        if (nivel.isEmpty() || quais.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return benchmark.iniciar(nivel.get(), quais.get())
                .map(andamento -> ResponseEntity.status(HttpStatus.ACCEPTED).body(andamento))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT).body(benchmark.andamento()));
    }

    @GetMapping("/api/holograma/benchmark")
    public BenchmarkDeHolograma.Andamento andamento() {
        return benchmark.andamento();
    }

    /** A lista da query, sem repetição e na ordem pedida; nome desconhecido recusa a lista inteira. */
    static Optional<List<String>> provedores(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return Optional.of(BenchmarkDeHolograma.PROVEDORES);
        }
        List<String> lista = new ArrayList<>();
        for (String parte : bruto.split(",")) {
            String chave = parte.trim().toLowerCase(Locale.ROOT);
            String provedor = switch (chave) {
                case "local" -> "local";
                case "anthropic", "haiku" -> "anthropic";
                default -> null;
            };
            if (provedor == null) return Optional.empty();
            if (!lista.contains(provedor)) lista.add(provedor);
        }
        return Optional.of(List.copyOf(lista));
    }
}
