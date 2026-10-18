package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.conversa.OuvinteProgresso;
import tools.jackson.databind.json.JsonMapper;

class TarefasPcSemIdTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TarefasComputerUse tarefas = mock(TarefasComputerUse.class);
    private final OuvinteProgresso usuario = new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
    };

    @Test void statusSemIdConsultaUltimaTarefaESemUuidNaResposta() {
        when(tarefas.ultima(42)).thenReturn(new TarefasComputerUse.Retrato(
                "uuid-grande", "buscar academias", "executando", "", "", 2, 3, 0, 0, "", "agora"));
        var ferramenta = new StatusTarefaNoPcFerramenta(tarefas, json);
        assertFalse(ferramenta.esquemaEntrada().has("required"));
        String resposta = ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto();
        assertTrue(resposta.contains("buscar academias"));
        assertFalse(resposta.contains("uuid-grande"));
        verify(tarefas).ultima(42);
    }

    @Test void cancelamentoSemIdUsaUltimaTarefaDoUsuario() {
        when(tarefas.ultima(42)).thenReturn(new TarefasComputerUse.Retrato(
                "uuid-grande", "buscar academias", "executando", "", "", 2, 3, 0, 0, "", "agora"));
        when(tarefas.cancelar("uuid-grande", 42)).thenReturn(true);
        var ferramenta = new CancelarTarefaNoPcFerramenta(tarefas, json);
        assertFalse(ferramenta.esquemaEntrada().has("required"));
        assertEquals("Cancelamento solicitado.",
                ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto());
        verify(tarefas).cancelar("uuid-grande", 42);
    }
}
