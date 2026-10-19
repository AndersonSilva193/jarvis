package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class FluxoEdicaoPcTest {
    @Test void ultimaEdicaoSoFicaDisponivelDepoisDeConcluida() {
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.online()).thenReturn(true);
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class),
                mock(TelegramClient.class), JsonMapper.builder().build());
        String editId = "22222222-2222-2222-2222-222222222222";
        assertTrue(fluxo.aplicarDepoisDeConfirmar("aplicar_edicao", editId, "hud", 42)
                .contains("autorizada"));
        ArgumentCaptor<ComandoPc> comando = ArgumentCaptor.forClass(ComandoPc.class);
        verify(operacoes).enfileirar(comando.capture(), any(), any(), eq("hud"), eq(42L));
        assertTrue(fluxo.ultimaEdicaoConcluida(42).isEmpty());
        when(operacoes.estado(comando.getValue().id())).thenReturn(Optional.of(
                new OperacoesPcService.Retrato(comando.getValue().id(), "aplicar_edicao",
                        "concluído", "", "", "", "nota.md", null, false)));
        assertEquals(editId, fluxo.ultimaEdicaoConcluida(42).orElseThrow());
        assertTrue(fluxo.ultimaEdicaoConcluida(7).isEmpty());
    }
}
