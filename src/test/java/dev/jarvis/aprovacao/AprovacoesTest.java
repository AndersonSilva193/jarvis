package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.aprovacao.Aprovacoes.Risco;
import dev.jarvis.pc.RelogioFalso;

/** A aprovação humana de uso único: prazo por risco, mensagem nova, e o modelo fora da decisão. */
class AprovacoesTest {

    private final RelogioFalso relogio = new RelogioFalso();
    private final Aprovacoes aprovacoes = new Aprovacoes(relogio);
    private final AtomicInteger execucoes = new AtomicInteger();

    /** O que uma ferramenta passaria: tipo, descrição, pergunta de verdade e risco. */
    private String pedir(String tipo, Risco risco, String pedidoOrigem) {
        return aprovacoes.pedir(tipo, "acao", "Posso fazer a acao?", risco, 42, pedidoOrigem,
                () -> "feito " + execucoes.incrementAndGet()).codigo();
    }

    @Test
    @DisplayName("COMUM vale 10 minutos e CRITICO só 2")
    void prazoDependeDoRisco() {
        pedir("rotina", Risco.COMUM, "pedido-1");
        relogio.avancar(Duration.ofMinutes(9));
        assertEquals("feito 1", aprovacoes.tratar("confirmo", 42, "pedido-2").orElseThrow(),
                "9 minutos ainda estão dentro dos 10 do risco comum");

        pedir("rotina", Risco.COMUM, "pedido-3");
        relogio.avancar(Duration.ofMinutes(10));
        assertTrue(aprovacoes.tratar("confirmo", 42, "pedido-4").orElseThrow().contains("expirou"));

        pedir("pc", Risco.CRITICO, "pedido-5");
        relogio.avancar(Duration.ofMinutes(1));
        assertEquals("feito 2", aprovacoes.tratar("confirmo", 42, "pedido-6").orElseThrow());

        pedir("pc", Risco.CRITICO, "pedido-7");
        relogio.avancar(Duration.ofMinutes(2));
        assertTrue(aprovacoes.tratar("confirmo", 42, "pedido-8").orElseThrow().contains("expirou"),
                "desligar o PC não pode ficar 10 minutos em aberto");
        assertEquals(2, execucoes.get());
    }

    @Test
    @DisplayName("o prazoMaximo da proposta abaixa o prazo do risco, nunca o estica")
    void prazoMaximoAbaixaOPrazo() {
        Instant prazo = relogio.instant().plusSeconds(30);
        var confirmacao = aprovacoes.pedir("edicao", "Substituir nota.md",
                "Posso substituir o original de nota.md?", Risco.COMUM, 42, "pedido-1", prazo,
                () -> "feito " + execucoes.incrementAndGet());
        assertEquals(prazo.toString(), confirmacao.expiraEm(),
                "a aprovação não pode sobreviver à proposta preparada no PC");

        relogio.avancar(Duration.ofSeconds(30));
        assertTrue(aprovacoes.tratar("confirmo", 42, "pedido-2").orElseThrow().contains("expirou"));
        assertEquals(0, execucoes.get());
    }

    @Test
    @DisplayName("a proposta já vencida nem chega a virar aprovação")
    void propostaVencidaNaoViraAprovacao() {
        Instant prazo = relogio.instant().minusSeconds(1);
        assertThrows(IllegalStateException.class, () -> aprovacoes.pedir("edicao", "Substituir",
                "Posso substituir?", Risco.COMUM, 42, "pedido-1", prazo, () -> "indevido"));
    }

    @Test
    @DisplayName("a pergunta montada pela ferramenta volta no Confirmacao")
    void perguntaVaiNoConfirmacao() {
        var confirmacao = aprovacoes.pedir("rotina", "Executar rotina cadastro-nf",
                "Posso executar a rotina cadastro-nf no seu PC?", Risco.COMUM, 42, "pedido-1",
                () -> "feito");

        assertEquals("Posso executar a rotina cadastro-nf no seu PC?", confirmacao.pergunta());
        assertEquals("Executar rotina cadastro-nf", confirmacao.descricao());
    }

    @Test
    @DisplayName("sem pergunta própria, a descrição serve de pergunta")
    void perguntaVaziaCaiNaDescricao() {
        assertEquals("Executar rotina", aprovacoes.pedir("rotina", "Executar rotina", "  ",
                Risco.COMUM, 42, "pedido-1", () -> "feito").pergunta());
    }

    @Test
    @DisplayName("só mensagem posterior do mesmo usuário executa, e uma vez só")
    void soMensagemPosteriorAutenticadaExecutaUmaVez() {
        String codigo = pedir("computer", Risco.COMUM, "pedido-1");

        assertTrue(aprovacoes.tratar("confirmo pc " + codigo, 42, "pedido-1").orElseThrow()
                .contains("recusada"), "aprovar no mesmo pedido é o modelo se autoconfirmando");
        assertEquals(0, execucoes.get());
        assertTrue(aprovacoes.tratar("confirmo pc " + codigo, 7, "pedido-2").orElseThrow()
                .contains("recusada"), "outro usuário não aprova");
        assertEquals("feito 1", aprovacoes.tratar("confirmo pc " + codigo, 42, "pedido-2").orElseThrow());
        assertTrue(aprovacoes.tratar("confirmo pc " + codigo, 42, "pedido-3").orElseThrow()
                .contains("Nao ha"), "uso único");
        assertEquals(1, execucoes.get());
    }

    @Test
    @DisplayName("usuário zero nem consegue pedir aprovação")
    void usuarioNaoAutenticadoNaoPede() {
        assertThrows(IllegalArgumentException.class, () -> aprovacoes.pedir("pc", "acao",
                "Posso?", Risco.CRITICO, 0, "pedido-1", () -> "indevido"));
        assertThrows(IllegalArgumentException.class, () -> aprovacoes.pedir("pc", "acao",
                "Posso?", Risco.CRITICO, 42, "  ", () -> "indevido"));
    }

    @Test
    @DisplayName("código errado deixa pendente; cancelar esquece")
    void codigoErradoECancelamento() {
        String codigo = pedir("rotina", Risco.COMUM, "origem");
        String errado = codigo.equals("000000") ? "000001" : "000000";

        assertTrue(aprovacoes.tratar("confirmo rotina " + errado, 42, "novo").orElseThrow()
                .contains("incorreto"));
        assertEquals("Operacao cancelada.", aprovacoes.tratar("cancelar", 42, "novo").orElseThrow());
        assertEquals(0, execucoes.get());
        assertTrue(aprovacoes.tratar("confirmo rotina " + codigo, 42, "outro").orElseThrow()
                .contains("Nao ha"));
    }

    @Test
    @DisplayName("as frases naturais de hoje confirmam qualquer tipo")
    void frasesNaturaisConfirmamTodosOsTipos() {
        for (String tipo : new String[] { "computer", "rotina", "pc", "edicao" }) {
            for (String frase : new String[] { "confirmo", "CONFIRMO A TAREFA!",
                    " Confirmo   a operação. ", "Pode prosseguir?", "pode proceguir",
                    "pode continuar", "pode seguir", "pode executar", "Sim, pode prosseguir." }) {
                Aprovacoes isolado = new Aprovacoes(new RelogioFalso());
                AtomicInteger vezes = new AtomicInteger();
                isolado.pedir(tipo, "acao", "Posso?", Risco.COMUM, 42, "origem",
                        () -> "feito " + vezes.incrementAndGet());

                assertEquals("feito 1", isolado.tratar(frase, 42, "novo").orElseThrow(),
                        tipo + ": " + frase);
                assertTrue(isolado.tratar(frase, 42, "outro").orElseThrow().contains("Nao ha"));
            }
        }
    }

    @Test
    @DisplayName("mensagem que não é confirmação nem cancelamento segue para o modelo")
    void mensagemComumNaoEDecisao() {
        pedir("computer", Risco.COMUM, "origem");

        assertTrue(aprovacoes.tratar("confirmo qualquer coisa", 42, "novo").isEmpty());
        assertTrue(aprovacoes.tratar("que horas são?", 42, "novo").isEmpty());
        assertTrue(aprovacoes.tratar(null, 42, "novo").isEmpty());
        assertEquals(0, execucoes.get());
    }

    @Test
    @DisplayName("as mensagens antigas com código continuam valendo")
    void mensagensAntigasComCodigoContinuamValidas() {
        for (String tipo : new String[] { "computer", "rotina", "pc" }) {
            Aprovacoes isolado = new Aprovacoes(new RelogioFalso());
            String codigo = isolado.pedir(tipo, "acao", "Posso?", Risco.COMUM, 42, "origem",
                    () -> "feito").codigo();
            String frase = tipo.equals("rotina") ? "confirmo rotina " : "confirmo pc ";

            assertEquals("feito", isolado.tratar(frase + codigo, 42, "novo").orElseThrow());
        }
    }

    @Test
    @DisplayName("pedido novo substitui a pendência sem executar a anterior")
    void pedidoNovoSubstituiSemExecutarAnterior() {
        AtomicInteger antigas = new AtomicInteger();
        String codigoAntigo = aprovacoes.pedir("computer", "buscar academias", "Posso buscar?",
                Risco.COMUM, 42, "pedido-1", () -> "antiga " + antigas.incrementAndGet()).codigo();

        assertThrows(IllegalStateException.class, () -> pedir("computer", Risco.COMUM, "pedido-1"),
                "no mesmo pedido o modelo trocaria a pendência que o usuário está vendo");
        assertThrows(IllegalStateException.class, () -> aprovacoes.pedir("computer", "outra",
                "Posso?", Risco.COMUM, 7, "pedido-2", () -> "indevido"));

        aprovacoes.pedir("computer", "abrir Battle.net", "Posso abrir?", Risco.COMUM, 42,
                "pedido-2", () -> "nova");
        assertTrue(aprovacoes.tratar("confirmo pc " + codigoAntigo, 42, "pedido-3")
                .orElseThrow().contains("incorreto"));
        assertEquals("nova", aprovacoes.tratar("confirmo", 42, "pedido-4").orElseThrow());
        assertEquals(0, antigas.get());
    }

    @Test
    @DisplayName("cancelar a tarefa visual exige mesmo usuário e mensagem nova")
    void cancelamentoDeTarefaPendente() {
        pedir("computer", Risco.COMUM, "pedido-1");

        assertEquals(Aprovacoes.Cancelamento.RECUSADA,
                aprovacoes.cancelarTarefaPendente(42, "pedido-1"));
        assertEquals(Aprovacoes.Cancelamento.RECUSADA,
                aprovacoes.cancelarTarefaPendente(7, "pedido-2"));
        assertEquals(Aprovacoes.Cancelamento.CANCELADA,
                aprovacoes.cancelarTarefaPendente(42, "pedido-2"));
        assertEquals(Aprovacoes.Cancelamento.INEXISTENTE,
                aprovacoes.cancelarTarefaPendente(42, "pedido-3"));

        // Só a tarefa visual: uma aprovação de rotina não é cancelada por aqui.
        pedir("rotina", Risco.COMUM, "pedido-4");
        assertEquals(Aprovacoes.Cancelamento.INEXISTENTE,
                aprovacoes.cancelarTarefaPendente(42, "pedido-5"));
        assertEquals(0, execucoes.get());
    }
}
