package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.atomic.DoubleAdder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.tarefas.ContextoDaTarefa;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class CustoPorTarefaTest {
    @TempDir Path pasta;
    @Test void tarefasConcorrentesTemCustoIsoladoSemDuplicarOTotal() throws Exception {
        var props = mock(JarvisProperties.class);
        when(props.zona()).thenReturn(ZoneId.of("UTC"));
        when(props.precosDeCusto()).thenReturn("modelo=1/1/1/1");
        when(props.precosDeContextoLongo()).thenReturn("");
        var medidor = new MedidorDeCusto(props, mock(TelegramClient.class), JsonMapper.builder().build(), new RelogioFalso(), pasta);
        var primeira = new DoubleAdder(); var segunda = new DoubleAdder();
        try {
            try (var contexto = ContextoDaTarefa.entrar("primeira", 42, primeira::add)) {
                medidor.registrar(Origem.COMPUTER_USE, "modelo", 1_000_000, 0, 0, 0, 0);
            }
            Thread outra = Thread.ofVirtual().start(() -> {
                try (var contexto = ContextoDaTarefa.entrar("segunda", 42, segunda::add)) {
                    medidor.registrar(Origem.BUSCA, "modelo", 2_000_000, 0, 0, 0, 0);
                }
            });
            medidor.registrar(Origem.CONVERSA, "modelo", 3_000_000, 0, 0, 0, 0);
            outra.join();
            assertEquals(1, primeira.sum()); assertEquals(2, segunda.sum());
            assertEquals(6, medidor.hoje().totalUsd());
            assertEquals(3, medidor.hoje().porOrigem().get(Origem.TAREFA));
            assertEquals(3, medidor.hoje().porOrigem().get(Origem.CONVERSA));
            assertFalse(medidor.hoje().porOrigem().containsKey(Origem.COMPUTER_USE));
            assertEquals(3, medidor.gastoAutonomoDeHoje());
        } finally { medidor.fechar(); }
    }
}
