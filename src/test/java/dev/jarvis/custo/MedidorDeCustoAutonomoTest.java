package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class MedidorDeCustoAutonomoTest {
    @TempDir Path pasta;
    @Test void somaSomenteAsTresOrigensERecuperaDoDisco() {
        var props = mock(JarvisProperties.class);
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.precosDeCusto()).thenReturn("modelo=1/1/1/1");
        when(props.precosDeContextoLongo()).thenReturn("");
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso();
        try (var medidor = new Fechavel(props, json, relogio, pasta)) {
            for (Origem origem : Origem.values()) medidor.valor.registrar(origem, "modelo", 1_000_000, 0, 0, 0, 0);
            assertEquals(3.0, medidor.valor.gastoAutonomoDeHoje(), 0.00001);
            medidor.valor.aguardar();
        }
        try (var medidor = new Fechavel(props, json, relogio, pasta)) {
            assertEquals(3.0, medidor.valor.gastoAutonomoDeHoje(), 0.00001);
        }
    }
    record Fechavel(MedidorDeCusto valor) implements AutoCloseable {
        Fechavel(JarvisProperties props, JsonMapper json, RelogioFalso relogio, Path pasta) {
            this(new MedidorDeCusto(props, mock(TelegramClient.class), json, relogio, pasta));
        }
        @Override public void close() { valor.fechar(); }
    }
}
