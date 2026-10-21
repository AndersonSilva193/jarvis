package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class FluxoEdicaoPcTest {
    private static OuvinteProgresso contexto(String canal, long usuario) {
        var contexto = mock(OuvinteProgresso.class);
        when(contexto.canal()).thenReturn(canal);
        when(contexto.usuarioAutenticado()).thenReturn(usuario);
        return contexto;
    }

    @Test void ultimaEdicaoSoFicaDisponivelDepoisDeConcluida() throws Exception {
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.online()).thenReturn(true);
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(
                chamada -> CompletableFuture.completedFuture(new ResultadoPc(
                        ((ComandoPc) chamada.getArgument(0)).id(), true, "nota.md", null, null, false)));
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class),
                mock(TelegramClient.class), JsonMapper.builder().build());
        String editId = "22222222-2222-2222-2222-222222222222";
        assertEquals("Atualizei nota.md. Salvo, mas a entrega do arquivo falhou.",
                fluxo.aplicar("aplicar_edicao", editId, contexto("hud", 42),
                        nome -> "Atualizei " + nome + ".", true).texto());
        ArgumentCaptor<ComandoPc> comando = ArgumentCaptor.forClass(ComandoPc.class);
        verify(operacoes).enfileirar(comando.capture(), any(), any(), eq("hud"), eq(42L));
        assertEquals(editId, comando.getValue().dados().path("editId").asText());
        assertTrue(fluxo.ultimaEdicaoConcluida(42).isEmpty());
        when(operacoes.estado(comando.getValue().id())).thenReturn(Optional.of(
                new OperacoesPcService.Retrato(comando.getValue().id(), "aplicar_edicao",
                        "concluído", "", "", "", "nota.md", null, false)));
        assertEquals(editId, fluxo.ultimaEdicaoConcluida(42).orElseThrow());
        assertTrue(fluxo.ultimaEdicaoConcluida(7).isEmpty());
    }

    @Test void copiaNaoEntraNasUltimasEdicoes() throws Exception {
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.online()).thenReturn(true);
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(
                chamada -> CompletableFuture.completedFuture(new ResultadoPc(
                        ((ComandoPc) chamada.getArgument(0)).id(), true, "copia.xlsx", null, null, false)));
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class),
                mock(TelegramClient.class), JsonMapper.builder().build());
        fluxo.aplicar("aplicar_edicao", "33333333-3333-3333-3333-333333333333",
                contexto("telegram", 42), nome -> "Cópia criada: " + nome + ".", false);
        assertTrue(fluxo.ultimaEdicaoConcluida(42).isEmpty());
        verify(operacoes, never()).estado(anyString());
    }

    @Test void pcOfflineNaoEnfileira() throws Exception {
        var operacoes = mock(OperacoesPcService.class);
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class),
                mock(TelegramClient.class), JsonMapper.builder().build());
        assertTrue(fluxo.aplicar("aplicar_edicao", "44444444-4444-4444-4444-444444444444",
                contexto("hud", 42), nome -> "Atualizei " + nome + ".", true).texto()
                .contains("offline"));
        verify(operacoes, never()).enfileirar(any(), any(), any(), anyString(), anyLong());
    }
}
