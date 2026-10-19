package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.conversa.AnexosService;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class AvisosOperacoesPcTest {
    @TempDir Path pasta;

    @Test void avisoDeEdicaoMostraSomenteONomeDoArquivo() {
        String comandoId = "e321f623-1bac-400b-b00f-5c1ffe259544";
        var estado = new OperacoesPcService.Retrato(comandoId, "aplicar_edicao",
                "concluído", "", "", "", "relatorio_assistentes_ia.md", null, false);
        var dados = JsonMapper.builder().build().createObjectNode()
                .put("editId", "7ab3662a-b441-4513-8842-b8300521811e")
                .put("versao", "a".repeat(64));
        var resultado = new ResultadoPc(comandoId, true, "relatorio_assistentes_ia.md",
                null, null, true, dados);

        assertEquals("Arquivo atualizado: relatorio_assistentes_ia.md.",
                AvisosOperacoesPc.textoDoAviso(comandoId, estado, resultado));
    }

    @Test void avisoDeDesfazerMostraONomeDoArquivo() {
        var estado = new OperacoesPcService.Retrato("id", "aplicar_desfazer",
                "concluído", "", "", "", "nota.txt", null, false);
        var resultado = new ResultadoPc("id", true, "nota.txt", null, null, true, null);

        assertEquals("Edição desfeita: nota.txt.",
                AvisosOperacoesPc.textoDoAviso("id", estado, resultado));
    }

    @Test void falhaDeEntregaMostraNomeSemIdsNemJson() {
        var estado = new OperacoesPcService.Retrato("comando", "aplicar_edicao",
                "concluído", "", "", "", "salvo, entrega falhou: nota.md", null, false);
        var resultado = new ResultadoPc("comando", true, "salvo, entrega falhou: nota.md",
                null, null, false, null);
        assertEquals("Arquivo atualizado: nota.md; salvo, entrega falhou.",
                AvisosOperacoesPc.textoDoAviso("comando", estado, resultado));
    }

    @Test void imagemTardiaEntraNaHudUmaVez() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(),
                pasta.resolve("operacoes.json"), Clock.systemUTC());
        var comando = ComandoPc.nova("captura_tela", "", Instant.now());
        e2.enfileirar(comando, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        e2.proximo(Duration.ZERO); e2.recebido(comando.id()); e2.executando(comando.id());
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        e2.concluir(comando.id(), "concluído",
                new ResultadoPc(comando.id(), true, "print", png, "image/png"), null);
        e2.pedirAviso(comando.id());
        var conversas = mock(RegistroConversas.class);
        var anexos = new AnexosService();
        try {
            var avisos = new AvisosOperacoesPc(e2, mock(TelegramClient.class), conversas,
                    mock(ArquivosPcService.class), anexos);
            avisos.avisar(); avisos.avisar();
            verify(conversas, times(1)).registrarResultadoTardio(eq(42L), eq(comando.id()),
                    anyString(), argThat(lista -> lista.size() == 1));
            assertEquals(0, e2.avisosPendentes().size());
        } finally { anexos.fechar(); }
    }
}
