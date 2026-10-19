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

    @Test void confirmacaoDeEdicaoRespeitaPrazoDaProposta() {
        Relogio relogio = new Relogio();
        IntencoesPc guarda = new IntencoesPc(relogio);
        AtomicInteger execucoes = new AtomicInteger();
        Instant prazo = relogio.instant().plusSeconds(30);
        var confirmacao = guarda.pedir("edicao", "Substituir nota.md", 42,
                "pedido-1", prazo, () -> "feito " + execucoes.incrementAndGet());
        assertEquals(prazo.toString(), confirmacao.expiraEm());
        relogio.agora.set(prazo);
        assertTrue(guarda.tratar("confirmo", 42, "pedido-2").orElseThrow().contains("expirou"));
        assertEquals(0, execucoes.get());
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

    @Test void frasesNaturaisConfirmamTodosOsTipos() {
        for (String tipo : new String[] { "computer", "rotina", "pc" }) {
            for (String frase : new String[] { "confirmo", "CONFIRMO A TAREFA!",
                    " Confirmo   a operação. ", "Pode prosseguir?", "pode proceguir",
                    "pode continuar", "pode seguir", "pode executar", "Sim, pode prosseguir." }) {
                IntencoesPc guarda = new IntencoesPc(new Relogio());
                AtomicInteger execucoes = new AtomicInteger();
                guarda.pedir(tipo, "acao", 42, "origem", () -> "feito " + execucoes.incrementAndGet());
                assertEquals("feito 1", guarda.tratar(frase, 42, "novo").orElseThrow(), tipo + ": " + frase);
                assertEquals(1, execucoes.get());
                assertTrue(guarda.tratar(frase, 42, "outro").orElseThrow().contains("Nao ha"));
            }
        }
    }

    @Test void fraseCurtaAindaExigeUsuarioPedidoNovoEPrazo() {
        Relogio relogio = new Relogio();
        IntencoesPc guarda = new IntencoesPc(relogio);
        AtomicInteger execucoes = new AtomicInteger();
        guarda.pedir("computer", "acao", 42, "origem", () -> "feito " + execucoes.incrementAndGet());
        assertTrue(guarda.tratar("confirmo", 42, "origem").orElseThrow().contains("recusada"));
        assertTrue(guarda.tratar("confirmo", 7, "novo").orElseThrow().contains("recusada"));
        assertTrue(guarda.tratar("confirmo qualquer coisa", 42, "novo").isEmpty());
        assertEquals("Operacao do PC cancelada.", guarda.tratar("CANCELAR!", 42, "novo").orElseThrow());
        assertEquals(0, execucoes.get());
        guarda.pedir("computer", "acao", 42, "origem-2", () -> "feito " + execucoes.incrementAndGet());
        relogio.agora.set(relogio.agora.get().plusSeconds(121));
        assertTrue(guarda.tratar("confirmo", 42, "novo-2").orElseThrow().contains("expirou"));
        assertEquals(0, execucoes.get());
    }

    @Test void mensagensAntigasComCodigoContinuamValidas() {
        for (String tipo : new String[] { "computer", "rotina", "pc" }) {
            IntencoesPc guarda = new IntencoesPc(new Relogio());
            String codigo = guarda.pedir(tipo, "acao", 42, "origem", () -> "feito").codigo();
            String frase = tipo.equals("rotina") ? "confirmo rotina " : "confirmo pc ";
            assertEquals("feito", guarda.tratar(frase + codigo, 42, "novo").orElseThrow());
        }
    }

    @Test void novoPedidoAutenticadoSubstituiConfirmacaoSemExecutarAnterior() {
        IntencoesPc guarda = new IntencoesPc(new Relogio());
        AtomicInteger antigas = new AtomicInteger();
        String codigoAntigo = guarda.pedir("computer", "buscar academias", 42, "pedido-1",
                () -> "antiga " + antigas.incrementAndGet()).codigo();
        assertThrows(IllegalStateException.class, () -> guarda.pedir("computer", "outra", 42,
                "pedido-1", () -> "indevido"));
        assertThrows(IllegalStateException.class, () -> guarda.pedir("computer", "outra", 7,
                "pedido-2", () -> "indevido"));
        guarda.pedir("computer", "abrir Battle.net", 42, "pedido-2", () -> "nova");
        assertTrue(guarda.tratar("confirmo pc " + codigoAntigo, 42, "pedido-3")
                .orElseThrow().contains("incorreto"));
        assertEquals("nova", guarda.tratar("confirmo", 42, "pedido-4").orElseThrow());
        assertEquals(0, antigas.get());
    }

    @Test void cancelamentoDeTarefaPendenteExigeMesmoUsuarioEMensagemNova() {
        IntencoesPc guarda = new IntencoesPc(new Relogio());
        guarda.pedir("computer", "buscar academias", 42, "pedido-1", () -> "indevido");
        assertEquals(IntencoesPc.Cancelamento.RECUSADA,
                guarda.cancelarTarefaPendente(42, "pedido-1"));
        assertEquals(IntencoesPc.Cancelamento.RECUSADA,
                guarda.cancelarTarefaPendente(7, "pedido-2"));
        assertEquals(IntencoesPc.Cancelamento.CANCELADA,
                guarda.cancelarTarefaPendente(42, "pedido-2"));
        assertEquals(IntencoesPc.Cancelamento.INEXISTENTE,
                guarda.cancelarTarefaPendente(42, "pedido-3"));
        guarda.pedir("rotina", "outra", 42, "pedido-4", () -> "rotina");
        assertEquals(IntencoesPc.Cancelamento.INEXISTENTE,
                guarda.cancelarTarefaPendente(42, "pedido-5"));
    }
}
