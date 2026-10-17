package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import dev.jarvis.pc.PcService;
import tools.jackson.databind.json.JsonMapper;

class ConsultaArquivosPcTest {
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
        when(pc.enfileirar(any())).thenReturn(CompletableFuture.failedFuture(new java.io.IOException("rede caiu")));
        String resposta = new ListarArquivosFerramenta(pc, JsonMapper.builder().build())
                .executar(JsonMapper.builder().build().createObjectNode());
        assertTrue(resposta.contains("conexão com o PC falhou"));
        assertFalse(resposta.contains("rede caiu"));
    }
}
