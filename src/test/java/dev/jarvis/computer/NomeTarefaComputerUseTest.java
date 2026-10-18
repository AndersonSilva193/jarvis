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

    @Test void avisosNaoFalamIdentificador() {
        String inicio = TarefasComputerUse.avisoInicio("buscar academias");
        String fim = TarefasComputerUse.avisoFim("buscar academias", "concluido", "Encontrei três opções.");
        assertTrue(inicio.contains("buscar academias"));
        assertTrue(fim.contains("buscar academias"));
        assertFalse(inicio.contains("status_tarefa_no_pc"));
        assertFalse(fim.contains("status_tarefa_no_pc"));
    }
}
