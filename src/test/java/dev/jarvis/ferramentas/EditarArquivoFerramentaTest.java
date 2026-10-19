package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.IntencoesPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;

class EditarArquivoFerramentaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String EDIT = "22222222-2222-2222-2222-222222222222";
    private static final String HASH = "a".repeat(64);
    private OuvinteProgresso contexto(String pedidoId, String texto) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return pedidoId; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return texto; }
        };
    }
    @Test void confirmacaoUsaEditIdDaPropostaSemRepassarOperacoes() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "nota.md")
                .put("previa", "- velho\n+ novo").put("versaoAnterior", HASH)
                .put("versaoProposta", "b".repeat(64));
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        when(fluxo.aplicarDepoisDeConfirmar(eq("aplicar_edicao"), eq(EDIT), eq("telegram"), eq(42L)))
                .thenReturn("iniciada");
        IntencoesPc intencoes = new IntencoesPc();
        var ferramenta = new EditarArquivoFerramenta(fluxo, intencoes, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}]}");
        var resultado = ferramenta.executarDetalhado(pedido, contexto("primeiro", "acrescente x em nota.md"));
        assertNotNull(resultado.confirmacao());
        verify(fluxo, never()).aplicarDepoisDeConfirmar(anyString(), anyString(), anyString(), anyLong());
        assertEquals("iniciada", intencoes.tratar("confirmo", 42, "segundo").orElseThrow());
        verify(fluxo).aplicarDepoisDeConfirmar("aplicar_edicao", EDIT, "telegram", 42);
        assertTrue(intencoes.tratar("confirmo", 42, "terceiro").orElseThrow().contains("Nao ha"));
    }

    @Test void documentoNaoAutorizaEditarOutroArquivo() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "segredo.md")
                .put("previa", "- a\n+ b").put("versaoAnterior", HASH)
                .put("versaoProposta", "b".repeat(64));
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        var ferramenta = new EditarArquivoFerramenta(fluxo, new IntencoesPc(), json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"copia\",\"operacoes\":[{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}]}");
        var resultado = ferramenta.executarDetalhado(pedido, contexto("primeiro", "acrescente x em nota.md"));
        assertTrue(resultado.texto().contains("não foi identificado"));
        verify(fluxo, never()).aplicarCopia(anyString(), any(), anyString());
    }
}
