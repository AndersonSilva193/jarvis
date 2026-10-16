package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ArquivoNovoTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void recusaCaminhoFormulaInjetadaELimites() throws Exception {
        assertFalse(PedidoArquivo.validar(json.readTree("{\"nome\":\"../segredo\",\"formato\":\"txt\"}")).isEmpty());
        assertFalse(PedidoArquivo.validar(json.readTree("{\"nome\":\"a\",\"formato\":\"exe\"}")).isEmpty());
        assertFalse(PedidoArquivo.validar(json.readTree("{\"nome\":\"a\",\"formato\":\"pdf\",\"conteudo\":\"![x](https://x)\"}")).isEmpty());
        assertFalse(PedidoArquivo.validar(json.readTree("{\"nome\":\"a\",\"formato\":\"xlsx\",\"abas\":[{\"nome\":\"A\",\"colunas\":[{\"nome\":\"X\"}],\"linhas\":[[{\"tipo\":\"formula\",\"valor\":\"HYPERLINK(A1)\"}]]}]}")).isEmpty());
        assertTrue(PedidoArquivo.validar(json.readTree("{\"nome\":\"a\",\"formato\":\"xlsx\",\"abas\":[{\"nome\":\"A\",\"colunas\":[{\"nome\":\"X\"}],\"linhas\":[[{\"tipo\":\"texto\",\"valor\":\"=1+1\"}]]}]}")).isEmpty());
    }

    @Test void uploadVinculadoAoComandoERecusaResultadoTardio() throws Exception {
        PcService pc = new PcService();
        ArquivosPcService arquivos = new ArquivosPcService(pc);
        assertFalse(arquivos.guardar("desconhecido", new byte[] { 1 }));
        ComandoPc comando = ComandoPc.nova("criar_arquivo", "", Instant.now());
        pc.enfileirar(comando);
        assertTrue(arquivos.guardar(comando.id(), new byte[] { 1, 2 }));
        assertTrue(arquivos.guardar(comando.id(), new byte[] { 1, 2 }));
        assertFalse(arquivos.guardar(comando.id(), new byte[] { 1, 3 }));
        pc.cancelar(comando.id());
        assertFalse(pc.concluir(new ResultadoPc(comando.id(), true, "a.txt", null, null, true)));
        assertFalse(arquivos.guardar(comando.id(), new byte[] { 1 }));
        arquivos.fechar();
    }
}
