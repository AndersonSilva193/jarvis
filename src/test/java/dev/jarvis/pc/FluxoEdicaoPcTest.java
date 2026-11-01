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
    private dev.jarvis.conversa.ContextoArquivo arquivoSelecionado() {
        var arquivo = new dev.jarvis.conversa.ContextoArquivo();
        arquivo.registrar(JsonMapper.builder().build().createObjectNode()
                .put("id", "11111111-1111-1111-1111-111111111111").put("nome", "nota.md")
                .put("formato", "md").put("versaoEsperada", "a".repeat(64)), true);
        return arquivo;
    }

    @Test void timeoutBloqueiaNovaEdicaoAteStatusFinalEReleitura() throws Exception {
        var arquivo = arquivoSelecionado();
        var ouvinte = contexto("telegram", 42);
        when(ouvinte.contextoArquivo()).thenReturn(arquivo);
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.online()).thenReturn(true);
        @SuppressWarnings("unchecked")
        CompletableFuture<ResultadoPc> futuro = mock(CompletableFuture.class);
        when(futuro.get(anyLong(), any())).thenThrow(new java.util.concurrent.TimeoutException());
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenReturn(futuro);
        var arquivos = mock(ArquivosPcService.class);
        var json = JsonMapper.builder().build();
        var fluxo = new FluxoEdicaoPc(operacoes, arquivos, mock(TelegramClient.class), json);
        assertTrue(fluxo.aplicar("aplicar_edicao", "22222222-2222-2222-2222-222222222222", ouvinte,
                nome -> "Atualizei " + nome, true).texto().contains("andamento"));
        String id = arquivo.estado().operacaoPendente();
        assertFalse(id.isEmpty());
        assertTrue(arquivo.erroEdicao(arquivo.estado().id(), arquivo.estado().versao()).contains(id));
        var status = new dev.jarvis.ferramentas.StatusOperacaoPcFerramenta(operacoes, arquivos, json);
        when(operacoes.estado(id)).thenReturn(Optional.of(new OperacoesPcService.Retrato(id, "aplicar_edicao",
                "resultado_incerto", "", "", "", "", null, false)));
        status.executarDetalhado(json.createObjectNode().put("id", id), ouvinte);
        assertEquals(id, arquivo.estado().operacaoPendente());
        when(operacoes.estado(id)).thenReturn(Optional.of(new OperacoesPcService.Retrato(id, "aplicar_edicao",
                "concluído", "", "", "", "nota.md", null, false)));
        when(operacoes.resultado(id)).thenReturn(Optional.of(new ResultadoPc(id, true, "nota.md", null, null,
                false, json.createObjectNode().put("versao", "b".repeat(64)))));
        status.executarDetalhado(json.createObjectNode().put("id", id), ouvinte);
        assertEquals("", arquivo.estado().operacaoPendente());
        assertEquals("b".repeat(64), arquivo.estado().versao());
        assertTrue(arquivo.erroEdicao(arquivo.estado().id(), arquivo.estado().versao()).contains("Releia"));
    }

    @Test void desfazerInvalidaLeituraMesmoSemAnexo() throws Exception {
        var arquivo = arquivoSelecionado();
        var ouvinte = contexto("telegram", 42);
        when(ouvinte.contextoArquivo()).thenReturn(arquivo);
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.online()).thenReturn(true);
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc c = chamada.getArgument(0);
            return CompletableFuture.completedFuture(new ResultadoPc(c.id(), true, "nota.md", null, null, false));
        });
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class), mock(TelegramClient.class), JsonMapper.builder().build());
        fluxo.aplicar("aplicar_desfazer", "22222222-2222-2222-2222-222222222222", ouvinte, nome -> "Voltei " + nome, false);
        assertTrue(arquivo.erroEdicao(arquivo.estado().id(), arquivo.estado().versao()).contains("Releia"));
        assertEquals("nota.md", arquivo.estado().nome());
    }
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
