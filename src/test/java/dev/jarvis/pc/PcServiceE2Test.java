package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class PcServiceE2Test {
    @TempDir Path pasta;
    @Test void comandosComEsemPayloadUsamE2QuandoAgenteAnuncia() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(), pasta.resolve("e2.json"),
                new RelogioFalso());
        e2.catalogo(List.of());
        var pc = new PcService(e2);
        var comando = ComandoPc.nova("volume", "subir", Instant.now());
        pc.enfileirar(comando);
        assertEquals(comando.id(), e2.proximo(Duration.ZERO).orElseThrow().comando().id());
        var arquivo = ComandoPc.nova("criar_arquivo", "", Instant.now());
        pc.enfileirar(arquivo);
        assertEquals("pendente", e2.estado(arquivo.id()).orElseThrow().estado());
    }
}
