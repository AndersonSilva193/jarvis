package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.TarefasPlanilhaService;
import tools.jackson.databind.json.JsonMapper;

class AgendarTarefaPlanilhaFerramentaTest {

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    @Test void aPerguntaDizOQueVaiAcontecerEOImpacto() {
        var json = JsonMapper.builder().build();
        var ferramenta = new AgendarTarefaPlanilhaFerramenta(
                mock(TarefasPlanilhaService.class), new Aprovacoes(), json);
        var entrada = json.createObjectNode().put("idArquivo", ID).put("nomeArquivo", "dados.xlsx")
                .put("instrucao", "some a coluna C").put("cron", "0 0 8 * * *");
        OuvinteProgresso contexto = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido-1"; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return "todo dia de manhã, atualize dados.xlsx"; }
        };

        var resultado = ferramenta.executarDetalhado(entrada, contexto);

        String pergunta = "Vou agendar edições recorrentes em dados.xlsx (0 0 8 * * *); elas vão "
                + "rodar sozinhas até o senhor cancelar. Posso?";
        assertEquals(pergunta, resultado.confirmacao().pergunta());
        assertTrue(resultado.texto().endsWith(pergunta), resultado.texto());
    }
}
