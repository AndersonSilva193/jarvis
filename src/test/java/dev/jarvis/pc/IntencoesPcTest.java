package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class IntencoesPcTest {
    private static final class Relogio extends Clock {
        final AtomicReference<Instant> agora = new AtomicReference<>(Instant.parse("2026-10-18T12:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return agora.get(); }
    }

    @Test void soMensagemPosteriorAutenticadaExecutaUmaVez() {
        Relogio relogio = new Relogio();
        IntencoesPc guarda = new IntencoesPc(relogio);
        AtomicInteger execucoes = new AtomicInteger();
        String codigo = guarda.pedir("computer", "abrir tela ficticia", 42, "pedido-1",
                () -> "iniciada " + execucoes.incrementAndGet()).codigo();
        assertTrue(guarda.tratar("confirmo pc " + codigo, 42, "pedido-1").orElseThrow()
                .contains("recusada"));
        assertEquals(0, execucoes.get());
        assertTrue(guarda.tratar("confirmo pc " + codigo, 7, "pedido-2").orElseThrow()
                .contains("recusada"));
        assertEquals("iniciada 1", guarda.tratar("confirmo pc " + codigo, 42, "pedido-2").orElseThrow());
        assertEquals(1, execucoes.get());
        assertTrue(guarda.tratar("confirmo pc " + codigo, 42, "pedido-3").orElseThrow()
                .contains("Nao ha"));
        assertEquals(1, execucoes.get());
    }

    @Test void codigoErradoCancelamentoEExpiracao() {
        Relogio relogio = new Relogio();
        IntencoesPc guarda = new IntencoesPc(relogio);
        AtomicInteger execucoes = new AtomicInteger();
        String codigo = guarda.pedir("rotina", "rotina ficticia", 42, "origem",
                () -> "executada " + execucoes.incrementAndGet()).codigo();
        String errado = codigo.equals("000000") ? "000001" : "000000";
        assertTrue(guarda.tratar("confirmo rotina " + errado, 42, "novo").orElseThrow()
                .contains("incorreto"));
        assertEquals("Operacao do PC cancelada.", guarda.tratar("cancelar", 42, "novo").orElseThrow());
        assertEquals(0, execucoes.get());
        String outro = guarda.pedir("rotina", "rotina ficticia", 42, "origem-2",
                () -> "executada " + execucoes.incrementAndGet()).codigo();
        relogio.agora.set(relogio.agora.get().plusSeconds(121));
        assertTrue(guarda.tratar("confirmo rotina " + outro, 42, "novo-2").orElseThrow()
                .contains("expirou"));
        assertEquals(0, execucoes.get());
    }
}
