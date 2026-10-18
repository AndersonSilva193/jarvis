package dev.jarvis.pc;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Confirmação vinculada ao texto recebido do usuário, nunca a texto produzido pelo modelo. */
@Component
public class GuardaDeRotina {
    private record Pendente(String nome, String pedido, String codigo, Instant prazo) { }
    private final AtomicReference<Pendente> pendente = new AtomicReference<>();
    private final SecureRandom sorteio = new SecureRandom();

    public String pedir(String nome, String pedido) {
        String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
        pendente.set(new Pendente(nome, pedido, codigo, Instant.now().plusSeconds(120)));
        return codigo;
    }
    public String consumir(String nome, String textoDoUsuario) {
        Pendente p = pendente.get();
        if (p == null || Instant.now().isAfter(p.prazo()) || !p.nome().equals(nome)
                || !textoDoUsuario.trim().equals("confirmo rotina " + p.codigo())) return null;
        return pendente.compareAndSet(p, null) ? p.pedido() : null;
    }
    public boolean haPendente(String nome) {
        Pendente p = pendente.get();
        return p != null && p.nome().equals(nome) && Instant.now().isBefore(p.prazo());
    }
}
