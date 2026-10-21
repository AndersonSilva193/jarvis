package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;

class DesfazerEdicaoFerramentaTest {
    @SuppressWarnings("unchecked")
    @Test void desfazUltimaEdicaoSemExporIdOuHashAoUsuario() throws Exception {
        String editId = "22222222-2222-2222-2222-222222222222";
        var fluxo = mock(FluxoEdicaoPc.class);
        var json = JsonMapper.builder().build();
        when(fluxo.ultimaEdicaoConcluida(42)).thenReturn(Optional.of(editId));
        var dados = json.createObjectNode().put("nome", "nota.md")
                .put("previa", "- novo\n+ antigo")
                .put("hashAtual", "a".repeat(64)).put("hashRestaurado", "b".repeat(64));
        when(fluxo.preparar(eq("preparar_desfazer"), any(), any())).thenReturn(
                new ResultadoPc("comando", true, "ok", null, null, false, dados));
        when(fluxo.aplicar(anyString(), anyString(), any(), any(), anyBoolean())).thenAnswer(
                chamada -> ResultadoFerramenta.texto(
                        ((UnaryOperator<String>) chamada.getArgument(3)).apply("nota.md")));
        var ferramenta = new DesfazerEdicaoFerramenta(fluxo, json);
        OuvinteProgresso contexto = new OuvinteProgresso() {
            @Override public String idPedido() { return "pedido"; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String pedidoOriginal() { return "desfaça a edição desse arquivo"; }
            @Override public String canal() { return "hud"; }
        };
        var resultado = ferramenta.executarDetalhado(json.createObjectNode(), contexto);
        assertNull(resultado.confirmacao());
        assertEquals("Voltei nota.md para a versão anterior.", resultado.texto());
        assertFalse(resultado.texto().contains(editId));
        assertFalse(resultado.texto().contains("a".repeat(64)));
        // O editId vem de ultimaEdicaoConcluida, e é só ele que atravessa; o caminho do backup
        // nunca sai do agente.
        verify(fluxo).preparar(eq("preparar_desfazer"),
                argThat(p -> editId.equals(p.path("editId").asText())), eq(contexto));
        verify(fluxo).aplicar(eq("aplicar_desfazer"), eq(editId), eq(contexto), any(), eq(false));
    }

    @Test void semPedidoExplicitoNaoPrepara() throws Exception {
        var fluxo = mock(FluxoEdicaoPc.class);
        var json = JsonMapper.builder().build();
        when(fluxo.ultimaEdicaoConcluida(42))
                .thenReturn(Optional.of("22222222-2222-2222-2222-222222222222"));
        var ferramenta = new DesfazerEdicaoFerramenta(fluxo, json);
        var resultado = ferramenta.executarDetalhado(json.createObjectNode(),
                new OuvinteProgresso() {
                    @Override public String idPedido() { return "pedido"; }
                    @Override public long usuarioAutenticado() { return 42; }
                    @Override public String pedidoOriginal() { return "e aí, tudo bem?"; }
                    @Override public String canal() { return "hud"; }
                });
        assertTrue(resultado.texto().contains("pedido explícito"));
        verify(fluxo, never()).preparar(anyString(), any(), any());
        verify(fluxo, never()).aplicar(anyString(), anyString(), any(), any(), anyBoolean());
    }
}
