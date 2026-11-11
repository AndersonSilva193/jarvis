package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class AvisosEmpresaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @TempDir Path pasta;

    private OperacoesPcService.Retrato retrato(String estado, String mensagem) {
        return new OperacoesPcService.Retrato("id", "empresa_delegar", estado, "", "", "", mensagem, null, false);
    }

    private ResultadoPc concluido(boolean arquivo) {
        return new ResultadoPc("id", true, arquivo ? "empresa-20261111-1000.md" : "tarefa concluída", null, null,
                arquivo, json.createObjectNode().put("resumo", "Quatro conteúdos prontos."));
    }

    @Test void concluidaMostraOResumoSemIdDeOperacao() {
        assertEquals("A empresa concluiu a tarefa.\n\nQuatro conteúdos prontos.",
                AvisosOperacoesPc.textoDoAviso("id", retrato("concluído", "tarefa concluída"), concluido(false)));
    }

    @Test void falhaPedeAtencaoComOMotivo() {
        var resultado = new ResultadoPc("id", false, "O agente terminou sem entregar resposta.", null, null);
        assertEquals("A tarefa da empresa precisa da sua atenção: O agente terminou sem entregar resposta.",
                AvisosOperacoesPc.textoDoAviso("id", retrato("falhou", "O agente terminou sem entregar resposta."), resultado));
    }

    @Test void semResultadoAindaAvisaOQueHouve() {
        assertEquals("A tarefa da empresa precisa da sua atenção: agente não confirmou o resultado após o prazo",
                AvisosOperacoesPc.textoDoAviso("id", retrato("resultado_incerto",
                        "agente não confirmou o resultado após o prazo"), null));
    }

    @Test void resultadoLongoChegaComoDocumentoEComResumo() throws Exception {
        Path arquivo = Files.writeString(pasta.resolve("r.md"), "completo");
        var operacoes = mock(OperacoesPcService.class);
        var telegram = mock(TelegramClient.class);
        var arquivos = mock(ArquivosPcService.class);
        var anexos = mock(AnexosService.class);
        when(operacoes.avisosPendentes()).thenReturn(List.of("id"));
        when(operacoes.estado("id")).thenReturn(Optional.of(retrato("concluído", "empresa-20261111-1000.md")));
        when(operacoes.marcarAviso("id")).thenReturn(true);
        when(operacoes.resultado("id")).thenReturn(Optional.of(concluido(true)));
        when(operacoes.canal("id")).thenReturn("telegram");
        when(operacoes.chatId("id")).thenReturn(42L);
        when(arquivos.obter("id")).thenReturn(Optional.of(arquivo));

        new AvisosOperacoesPc(operacoes, telegram, mock(RegistroConversas.class), arquivos, anexos).avisar();

        verify(telegram).enviarDocumento(eq(42L), any(), eq("empresa-20261111-1000.md"));
        verify(telegram).enviarMensagem(eq(42L), eq("A empresa concluiu a tarefa.\n\nQuatro conteúdos prontos."));
        assertTrue(Files.exists(arquivo));
    }

    @Test void uploadDoResultadoEAceitoDuranteAExecucao() {
        var operacoes = mock(OperacoesPcService.class);
        when(operacoes.estado("id")).thenReturn(Optional.of(retrato("executando", "")));
        assertTrue(new PcService(operacoes).aguardandoArquivo("id"));
    }
}
