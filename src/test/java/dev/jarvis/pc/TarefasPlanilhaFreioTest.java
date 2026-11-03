package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import dev.jarvis.agente.Freio;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;
import dev.jarvis.proativo.*;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class TarefasPlanilhaFreioTest {
    @TempDir Path pasta;
    @Test @SuppressWarnings("unchecked") void pausaDescartaVencidasSemRevogarAgendaENaoExecutaAoRetomar() throws Exception {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("UTC"));
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso("2026-11-03T12:00:10Z");
        var freio = new Freio(props, mock(MedidorDeCusto.class), mock(AvisosProativos.class), new FalasService(), json, relogio, pasta.resolve("freio.json"));
        var assistente = mock(Assistente.class);
        ObjectProvider<Assistente> provedor = mock(ObjectProvider.class); when(provedor.getObject()).thenReturn(assistente);
        var tarefas = new TarefasPlanilhaService(json, pasta.resolve("tarefas.json"), props.zona(), relogio, provedor, mock(TelegramClient.class));
        tarefas.configurarFreio(freio);
        var tarefa = tarefas.registrar(42, "11111111-1111-1111-1111-111111111111", "dados.xlsx", "atualize o estoque", "0 * * * * *", false);
        freio.pausar("hud"); relogio.avancar(Duration.ofMinutes(2));
        // Nenhum tique durante a pausa: a retomada precisa descartar o vencimento por si só.
        freio.retomar("hud"); tarefas.executarPendentes();
        assertTrue(tarefas.listar(42).getFirst().ativa());
        assertTrue(tarefas.listar(42).getFirst().proxima().isAfter(relogio.instant()));
        verifyNoInteractions(assistente);
        tarefas.executarPendentes(); verifyNoInteractions(assistente);
        relogio.avancar(Duration.ofMinutes(1)); tarefas.executarPendentes();
        verify(assistente, timeout(2000).times(1)).executarTarefa(anyString(), eq(Canal.TAREFA),
                argThat(c -> c.idTarefaPlanilha().equals(tarefa.id()) && c.geracaoAutonoma() == freio.geracao()));
    }
}
