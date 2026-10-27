package dev.jarvis.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NomeTarefaComputerUseTest {
    @Test void nomeVemDoInicioDoObjetivo() {
        assertEquals("buscar academias perto de casa",
                TarefasComputerUse.nomeCurto("Buscar academias perto de casa. Compare horários."));
        assertEquals("tarefa no computador", TarefasComputerUse.nomeCurto("..."));
    }

    @Test void nomeRespeitaSeisPalavrasESessentaCaracteres() {
        assertEquals("buscar academias perto de casa para",
                TarefasComputerUse.nomeCurto("Buscar academias perto de casa para amanhã com piscina"));
        assertEquals(60, TarefasComputerUse.nomeCurto("a".repeat(80)).length());
    }

    @Test void resultadoFinalNaoCortaNoMeioDoTelefone() {
        // O texto real que chegou cortado em "Tel": três fichas passam de 300 caracteres fácil.
        String fichas = "Concluído: li as fichas de três academias.\n\n"
                + "1. Jango Fight Centro de Treinamento\nEndereço: Rua Lydia Girardi Bertholdi, 120\n"
                + "Nota: 4,9 (22 avaliações)\nTelefone: (41) 99999-0001\n".repeat(3);
        assertTrue(fichas.length() > 300);
        assertEquals(fichas.strip(), TarefasComputerUse.resultadoFinal(fichas));
    }

    @Test void resultadoEnormeAvisaOndeCortou() {
        String enorme = "x".repeat(TarefasComputerUse.MAX_RESULTADO + 50);
        String r = TarefasComputerUse.resultadoFinal(enorme);
        assertTrue(r.startsWith("x".repeat(TarefasComputerUse.MAX_RESULTADO)));
        assertTrue(r.endsWith("[resultado cortado em " + TarefasComputerUse.MAX_RESULTADO + " caracteres]"));
    }

    @Test void avisosNaoFalamIdentificador() {
        String inicio = TarefasComputerUse.avisoInicio("buscar academias");
        String fim = TarefasComputerUse.avisoFim("buscar academias", "concluido", "Encontrei três opções.");
        assertTrue(inicio.contains("buscar academias"));
        assertTrue(fim.contains("buscar academias"));
        assertFalse(inicio.contains("status_tarefa_no_pc"));
        assertFalse(fim.contains("status_tarefa_no_pc"));
    }
}
