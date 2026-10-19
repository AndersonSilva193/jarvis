package dev.jarvis.pc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.telegram.TelegramClient;

class AvisosEdicaoTest {
    @Test void anexoAusenteAposSalvarNaoRepeteEdicaoEInformaFalhaDeEntrega() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        OperacoesPcService operacoes = mock(OperacoesPcService.class);
        ArquivosPcService arquivos = mock(ArquivosPcService.class);
        TelegramClient telegram = mock(TelegramClient.class);
        RegistroConversas conversas = mock(RegistroConversas.class);
        AnexosService anexos = mock(AnexosService.class);
        when(operacoes.avisosPendentes()).thenReturn(List.of(id));
        when(operacoes.estado(id)).thenReturn(Optional.of(new OperacoesPcService.Retrato(
                id, "aplicar_edicao", "concluído", "2026-01-01T00:00:00Z", "", "", "nota.md", null, false)));
        when(operacoes.marcarAviso(id)).thenReturn(true);
        when(operacoes.resultado(id)).thenReturn(Optional.of(
                new ResultadoPc(id, true, "nota.md", null, null, true)));
        when(operacoes.canal(id)).thenReturn("telegram");
        when(operacoes.chatId(id)).thenReturn(42L);
        when(arquivos.obter(id)).thenReturn(Optional.empty());
        new AvisosOperacoesPc(operacoes, telegram, conversas, arquivos, anexos).avisar();
        verify(telegram).enviarMensagem(eq(42L), contains("salvo, entrega falhou"));
        verify(operacoes, times(1)).marcarAviso(id);
    }
}
