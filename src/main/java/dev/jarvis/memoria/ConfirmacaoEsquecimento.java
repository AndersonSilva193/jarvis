package dev.jarvis.memoria;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import dev.jarvis.util.ComparadorDeToken;

/** Confirmação amarrada à identidade e a uma nova mensagem do usuário. */
@Component
public class ConfirmacaoEsquecimento {
    private record Pendente(String id, long revisao, String codigo, String pedido, Instant expira) { }
    private final Map<Long, Pendente> pendentes = new HashMap<>();
    private final SecureRandom sorteio = new SecureRandom();
    private final Clock relogio;

    public ConfirmacaoEsquecimento() { this(Clock.systemUTC()); }
    public ConfirmacaoEsquecimento(Clock relogio) { this.relogio = relogio; }

    public synchronized String pedir(long usuario, String id, long revisao, String pedido) {
        String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
        pendentes.put(usuario, new Pendente(id, revisao, codigo, pedido, relogio.instant().plusSeconds(120)));
        return codigo;
    }

    public synchronized long confirmar(long usuario, String id, String codigo, String pedido,
                                       String textoOriginal) {
        Pendente p = pendentes.remove(usuario);
        if (p == null || relogio.instant().isAfter(p.expira()) || p.pedido().equals(pedido)
                || !p.id().equals(id) || !ComparadorDeToken.igual(p.codigo(), codigo)
                || !textoOriginal.trim().equalsIgnoreCase("confirmo esquecer " + id + " " + codigo))
            throw new IllegalArgumentException("Confirmação ausente, vencida ou inválida; peça para esquecer novamente");
        return p.revisao();
    }
}
