package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;

class ConsultaArquivosPcTest {
    private static final String ID = "a81517b3-bc2a-4857-882d-bb0fba2f9e6d";

    @Test void nomeNoLugarDoIdVoltaAoModeloSemIrAoPc() throws Exception {
        PcService pc = mock(PcService.class);
        when(pc.online()).thenReturn(true);
        var json = JsonMapper.builder().build();
        String resposta = new LerArquivoFerramenta(pc, json).executar(json.createObjectNode()
                .put("id", "gasto_jarvis_atualizado_2026-10-27.xlsx").put("aba", "Resumo"));
        assertTrue(resposta.contains("não é um id do catálogo"), resposta);
        assertTrue(resposta.contains("listar_arquivos"), resposta);
        verify(pc, never()).enfileirar(any(), any(), anyLong());
    }

    @Test void lerArquivoUsaPaginasDeAteSeisMilCaracteres() throws Exception {
        PcService pc = mock(PcService.class);
        var json = JsonMapper.builder().build();
        var enviado = new java.util.concurrent.atomic.AtomicReference<ComandoPc>();
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), any(), anyLong())).thenAnswer(chamada -> {
            ComandoPc comando = chamada.getArgument(0);
            enviado.set(comando);
            return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true, "ok",
                    null, null, false, json.createObjectNode().put("conteudo", "página")));
        });
        var ler = new LerArquivoFerramenta(pc, json);
        assertEquals(6000, ler.esquemaEntrada().path("properties")
                .path("maximoCaracteres").path("maximum").intValue());
        ler.executar(json.createObjectNode().put("id", ID).put("maximoCaracteres", 12000));
        assertEquals(6000, enviado.get().dados().path("maximoCaracteres").intValue());
        ler.executar(json.createObjectNode().put("id", ID).put("inicio", 6000));
        assertEquals(6000, enviado.get().dados().path("maximoCaracteres").intValue());
        assertEquals(6000, enviado.get().dados().path("inicio").intValue());
    }

    @Test void pcOfflineExplicaIndisponibilidadeDosOriginais() throws Exception {
        PcService pc = new PcService();
        var json = JsonMapper.builder().build();
        var listar = new ListarArquivosFerramenta(pc, json);
        var ler = new LerArquivoFerramenta(pc, json);
        assertTrue(listar.executar(json.createObjectNode()).contains("originais locais estão indisponíveis"));
        assertTrue(ler.executar(json.createObjectNode().put("id", "doc-1"))
                .contains("originais locais estão indisponíveis"));
        assertEquals(0, pc.naFila());
    }

    @Test void falhaDeConexaoNaoExpoeExcecaoAoModelo() throws Exception {
        PcService pc = mock(PcService.class);
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), any(), anyLong()))
                .thenReturn(CompletableFuture.failedFuture(new java.io.IOException("rede caiu")));
        String resposta = new ListarArquivosFerramenta(pc, JsonMapper.builder().build())
                .executar(JsonMapper.builder().build().createObjectNode());
        assertTrue(resposta.contains("conexão com o PC falhou"));
        assertFalse(resposta.contains("rede caiu"));
    }
}
