package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.OperacoesPcService;
import tools.jackson.databind.json.JsonMapper;

class RodarRotinaFerramentaTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private OuvinteProgresso contexto(String idPedido) {
        return new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return idPedido; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return "rode os testes do agendabarber"; }
        };
    }

    @Test void pedeAprovacaoComPerguntaNaturalEmVezDeProtocolo() {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "testes_agendabarber", "roda a suíte de testes", List.of("maven"))));
        when(e2.online()).thenReturn(true);
        var aprovacoes = new Aprovacoes();
        var ferramenta = new RodarRotinaFerramenta(e2, aprovacoes, json);

        var resultado = ferramenta.executarDetalhado(
                json.createObjectNode().put("nome", "testes_agendabarber"), contexto("pedido-1"));

        String pergunta = "Vou rodar a rotina testes_agendabarber no seu PC; ela executa código na "
                + "sua máquina e eu aviso quando terminar. Posso?";
        assertEquals(pergunta, resultado.confirmacao().pergunta());
        assertEquals(pergunta, resultado.texto(),
                "o modelo repete a pergunta; não há palavra exata a cobrar do usuário");
        verify(e2, never()).enfileirar(any(), any(), any(), anyString(), anyLong());
    }

    @Test void semAprovacaoNadaVaiAoPc() {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "testes_agendabarber", "roda a suíte de testes", List.of("maven"))));
        when(e2.online()).thenReturn(false);
        var ferramenta = new RodarRotinaFerramenta(e2, new Aprovacoes(), json);

        var resultado = ferramenta.executarDetalhado(
                json.createObjectNode().put("nome", "testes_agendabarber"), contexto("pedido-1"));

        assertNull(resultado.confirmacao());
        assertEquals("Agente do PC offline; rotina não iniciada.", resultado.texto());
    }
}
