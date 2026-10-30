package dev.jarvis.holograma.controle;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Troca o {@code HUD_TOKEN} por um bilhete de uso único para abrir o WebSocket do holograma.
 *
 * <p>O {@code WebSocket} do navegador não manda cabeçalho, então a credencial teria de ir na URL.
 * Em vez do token de verdade, que vale para sempre e para a API inteira, vai um bilhete que só abre
 * uma conexão e morre em {@link #VALIDADE}. Quem pede o bilhete passou pelo {@code TokenHudFiltro}.
 */
@Component
public class BilhetesDoCanal {

    public static final Duration VALIDADE = Duration.ofSeconds(30);

    private final Map<String, Instant> emitidos = new ConcurrentHashMap<>();
    private final Clock relogio;

    @Autowired
    public BilhetesDoCanal() {
        this(Clock.systemUTC());
    }

    BilhetesDoCanal(Clock relogio) {
        this.relogio = relogio;
    }

    public String emitir() {
        Instant agora = relogio.instant();
        // Quem pede bilhete e não usa deixa lixo: cada emissão varre os vencidos.
        emitidos.values().removeIf(expira -> !expira.isAfter(agora));
        String bilhete = Segredos.novo();
        emitidos.put(bilhete, agora.plus(VALIDADE));
        return bilhete;
    }

    /** Verdadeiro uma vez só por bilhete, e só dentro da validade. */
    public boolean consumir(String bilhete) {
        if (!Segredos.formatoValido(bilhete)) {
            return false;
        }
        Instant expira = emitidos.remove(bilhete);
        return expira != null && relogio.instant().isBefore(expira);
    }
}
