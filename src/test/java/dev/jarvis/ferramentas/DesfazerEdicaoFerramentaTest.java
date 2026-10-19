package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.IntencoesPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;

class DesfazerEdicaoFerramentaTest {
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
        var ferramenta = new DesfazerEdicaoFerramenta(fluxo, new IntencoesPc(), json);
        OuvinteProgresso contexto = new OuvinteProgresso() {
            @Override public String idPedido() { return "pedido"; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String pedidoOriginal() { return "desfaça a edição desse arquivo"; }
            @Override public String canal() { return "hud"; }
        };
        var resultado = ferramenta.executarDetalhado(json.createObjectNode(), contexto);
        assertNotNull(resultado.confirmacao());
        assertFalse(resultado.texto().contains(editId));
        assertFalse(resultado.texto().contains("a".repeat(64)));
        verify(fluxo).preparar(eq("preparar_desfazer"),
                argThat(p -> editId.equals(p.path("editId").asText())), eq(contexto));
    }
}
