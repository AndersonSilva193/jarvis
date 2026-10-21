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
import dev.jarvis.aprovacao.Aprovacoes;
import tools.jackson.databind.json.JsonMapper;

class TarefasPcSemIdTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final TarefasComputerUse tarefas = mock(TarefasComputerUse.class);
    private final Aprovacoes aprovacoes = new Aprovacoes();
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
        var ferramenta = new CancelarTarefaNoPcFerramenta(tarefas, aprovacoes, json);
        assertFalse(ferramenta.esquemaEntrada().has("required"));
        assertEquals("Cancelamento solicitado.",
                ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto());
        verify(tarefas).cancelar("uuid-grande", 42);
    }

    @Test void cancelamentoSemIdRemoveConfirmacaoAntesDeBuscarTarefaIniciada() {
        aprovacoes.pedir("computer", "buscar academias", "Posso buscar academias?",
                Aprovacoes.Risco.COMUM, 42, "pedido-anterior", () -> "nao iniciar");
        var ferramenta = new CancelarTarefaNoPcFerramenta(tarefas, aprovacoes, json);
        assertEquals("Confirmação pendente da tarefa cancelada. Pode pedir outra tarefa.",
                ferramenta.executarDetalhado(json.createObjectNode(), usuario).texto());
        assertTrue(aprovacoes.tratar("confirmo", 42, "depois").orElseThrow().contains("Não há"));
        aprovacoes.pedir("computer", "abrir Battle.net", "Posso abrir o Battle.net?",
                Aprovacoes.Risco.COMUM, 42, "pedido-seguinte", () -> "novo pedido");
        verify(tarefas, org.mockito.Mockito.never()).ultima(42);
    }

    @Test void novoPedidoDeTarefaSubstituiConfirmacaoAntiga() {
        when(tarefas.disponivel()).thenReturn(true);
        when(tarefas.iniciar("abrir Battle.net", "hud", 42)).thenReturn("tarefa nova iniciada");
        var ferramenta = new TarefaNoPcFerramenta(tarefas, aprovacoes, json);
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
        assertEquals("Vou usar o monitor principal para: abrir Battle.net. Posso?", resposta.texto());
        assertEquals("tarefa nova iniciada", aprovacoes.tratar("confirmo", 42, "terceiro-pedido").orElseThrow());
    }

    @Test void descricaoApresentaUltimoRecursoSomenteQuandoDisponivel() {
        var ferramenta = new TarefaNoPcFerramenta(tarefas, aprovacoes, json);
        when(tarefas.disponivel()).thenReturn(false, true);
        assertTrue(ferramenta.descricao().contains("indisponível"));
        String disponivel = ferramenta.descricao();
        assertTrue(disponivel.contains("Último recurso"));
        assertTrue(disponivel.contains("Não diga que falta ferramenta"));
        assertTrue(disponivel.contains("pedir confirmação"));
        assertTrue(disponivel.contains("não peça palavra, código nem frase exata"));
        assertTrue(disponivel.contains("menu Iniciar"));
    }

    @Test void abrirBattleNetPeloMenuIniciarPedeConfirmacaoMesmoComLoja() {
        when(tarefas.disponivel()).thenReturn(true);
        var contexto = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido-battle"; }
            @Override public String canal() { return "hud"; }
            @Override public String pedidoOriginal() {
                return "Jarvis, abra o Battle.net no meu computador pelo menu Iniciar";
            }
        };
        var ferramenta = new TarefaNoPcFerramenta(tarefas, aprovacoes, json);
        var entrada = json.createObjectNode().put("objetivo",
                "Abrir Battle.net pelo menu Iniciar, sem realizar compras e sem digitar senha");
        var resposta = ferramenta.executarDetalhado(entrada, contexto);
        assertTrue(resposta.texto().startsWith("Vou usar o monitor principal para:"));
        assertTrue(resposta.texto().endsWith("Posso?"));
        assertTrue(resposta.confirmacao() != null);
        assertEquals(resposta.texto(), resposta.confirmacao().pergunta());
    }

    @Test void compraPagamentoESenhaComoAcaoContinuamBloqueados() {
        assertTrue(TarefaNoPcFerramenta.acaoSensivel("Comprar um jogo no Battle.net"));
        assertTrue(TarefaNoPcFerramenta.acaoSensivel("Pagar a fatura"));
        assertTrue(TarefaNoPcFerramenta.acaoSensivel("Digitar minha senha"));
        assertTrue(TarefaNoPcFerramenta.acaoSensivel("Usar o cartão de crédito"));
        assertFalse(TarefaNoPcFerramenta.acaoSensivel("Abrir a loja do Battle.net"));
        assertFalse(TarefaNoPcFerramenta.acaoSensivel("Abrir sem fazer compras"));
        assertFalse(TarefaNoPcFerramenta.acaoSensivel("Não pagar nem digitar senha"));
    }

    @Test void pedidoOriginalDeCompraNaoPodeSerDisfarcadoNoObjetivo() {
        when(tarefas.disponivel()).thenReturn(true);
        var contexto = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido-compra"; }
            @Override public String pedidoOriginal() { return "Compre um jogo no Battle.net"; }
        };
        var ferramenta = new TarefaNoPcFerramenta(tarefas, aprovacoes, json);
        var resposta = ferramenta.executarDetalhado(
                json.createObjectNode().put("objetivo", "Abrir o Battle.net"), contexto);
        assertEquals("Tarefa sensível proibida no computer use.", resposta.texto());
    }
}
