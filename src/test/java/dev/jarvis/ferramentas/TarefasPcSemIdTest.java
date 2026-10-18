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
import dev.jarvis.pc.IntencoesPc;
import tools.jackson.databind.json.JsonMapper;

class TarefasPcSemIdTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TarefasComputerUse tarefas = mock(TarefasComputerUse.class);
    private final IntencoesPc intencoes = new IntencoesPc();
    private final OuvinteProgresso usuario = new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
        @Override public String idPedido() { return "novo-pedido"; }
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
        var ferramenta = new CancelarTarefaNoPcFerramenta(tarefas, intencoes, json);
        assertFalse(ferramenta.esquemaEntrada().has("required"));
        assertEquals("Cancelamento solicitado.",
                ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto());
        verify(tarefas).cancelar("uuid-grande", 42);
    }

    @Test void cancelamentoSemIdRemoveConfirmacaoAntesDeBuscarTarefaIniciada() {
        intencoes.pedir("computer", "buscar academias", 42, "pedido-anterior", () -> "nao iniciar");
        var ferramenta = new CancelarTarefaNoPcFerramenta(tarefas, intencoes, json);
        assertEquals("Confirmacao pendente da tarefa cancelada. Pode pedir outra tarefa.",
                ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto());
        assertTrue(intencoes.tratar("confirmo", 42, "depois").orElseThrow().contains("Nao ha"));
        intencoes.pedir("computer", "abrir Battle.net", 42, "pedido-seguinte", () -> "novo pedido");
        verify(tarefas, org.mockito.Mockito.never()).ultima(42);
    }

    @Test void novoPedidoDeTarefaSubstituiConfirmacaoAntiga() {
        when(tarefas.disponivel()).thenReturn(true);
        when(tarefas.iniciar("abrir Battle.net", "hud", 42)).thenReturn("tarefa nova iniciada");
        var ferramenta = new TarefaNoPcFerramenta(tarefas, intencoes, json);
        var anterior = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido-anterior"; }
            @Override public String canal() { return "hud"; }
            @Override public String pedidoOriginal() { return "busque academias"; }
        };
        var novo = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "novo-pedido"; }
            @Override public String canal() { return "hud"; }
            @Override public String pedidoOriginal() { return "abra Battle.net"; }
        };
        ferramenta.executarDetalhado(json.createObjectNode().put("objetivo", "buscar academias"), anterior);
        var resposta = ferramenta.executarDetalhado(json.createObjectNode().put("objetivo", "abrir Battle.net"), novo);
        assertTrue(resposta.texto().contains("Confirme em NOVA mensagem"));
        assertEquals("tarefa nova iniciada", intencoes.tratar("confirmo", 42, "terceiro-pedido").orElseThrow());
    }

    @Test void descricaoApresentaUltimoRecursoSomenteQuandoDisponivel() {
        var ferramenta = new TarefaNoPcFerramenta(tarefas, intencoes, json);
        when(tarefas.disponivel()).thenReturn(false, true);
        assertTrue(ferramenta.descricao().contains("indisponivel"));
        String disponivel = ferramenta.descricao();
        assertTrue(disponivel.contains("Ultimo recurso"));
        assertTrue(disponivel.contains("Nao diga que falta ferramenta"));
        assertTrue(disponivel.contains("pedir confirmacao"));
    }
}
