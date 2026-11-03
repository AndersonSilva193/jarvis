package dev.jarvis.telegram;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import dev.jarvis.agente.*;
import dev.jarvis.briefing.BriefingService;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.ia.Assistente;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.*;
import tools.jackson.databind.json.JsonMapper;

class FreioTelegramTest {
    @TempDir Path pasta;
    @Test @SuppressWarnings("unchecked") void pausaAntesDaFilaERetomaSemModeloInclusiveSemPedidoAtivo() throws Exception {
        var props = mock(JarvisProperties.class);
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("teste", 42));
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        var json = JsonMapper.builder().build();
        var freio = new Freio(props, mock(MedidorDeCusto.class), mock(AvisosProativos.class), new FalasService(), json, new RelogioFalso(), pasta.resolve("freio.json"));
        var assistente = mock(Assistente.class); var tarefas = mock(TarefasComputerUse.class);
        ObjectProvider<Assistente> pa = mock(ObjectProvider.class); when(pa.getObject()).thenReturn(assistente);
        ObjectProvider<TarefasComputerUse> pt = mock(ObjectProvider.class); when(pt.getObject()).thenReturn(tarefas);
        var telegram = mock(TelegramClient.class);
        var poller = new TelegramPoller(telegram, assistente, mock(BriefingService.class), tarefas, props);
        poller.configurarFreio(new ControleDoFreio(freio, pa, pt));
        var mensagem = json.createObjectNode().put("text", "Jarvis, para tudo");
        mensagem.putObject("from").put("id", 99); mensagem.putObject("chat").put("id", 42);
        poller.receber(mensagem, 1);
        assertEquals(Freio.Estado.ATIVO, freio.retrato().estado());
        mensagem.withObject("/from").put("id", 42);
        poller.receber(mensagem, 2);
        assertEquals(Freio.Estado.PAUSADO, freio.retrato().estado());
        assertEquals(0, poller.tamanhoDaFila());
        verify(assistente).interromperPedidoAtual(42);
        verify(tarefas).cancelarUltima(42);
        verify(telegram).enviarMensagem(42, ControleDoFreio.PAREI);
        mensagem.put("text", "pode voltar"); poller.receber(mensagem, 3);
        assertEquals(Freio.Estado.ATIVO, freio.retrato().estado());
        verify(assistente, never()).responderTelegram(anyLong(), anyLong(), anyLong(), anyString());
        assertEquals(0, poller.tamanhoDaFila());
    }
}
