package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.google.GooglePlanilhasService;
import dev.jarvis.pc.ArquivosPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class CriarArquivoFluxoTest {
    @Test void ambasFerramentasDeclaramTipoDasCelulasAoModelo() {
        var json = JsonMapper.builder().build();
        var props = new JarvisProperties("Jarvis", "America/Sao_Paulo", "", null,
                null, null, null, null, null, null, null, null, null, null);
        var google = mock(GooglePlanilhasService.class);
        // O schema olha o configurado(), e não o autorizado(): este último faz um toque em disco e
        // mudaria o array tools quando a credencial aparecesse, levando o cache de prompt com ela.
        when(google.configurado()).thenReturn(true);
        Ferramenta[] ferramentas = {
                new CriarArquivoFerramenta(new PcService(), mock(ArquivosPcService.class),
                        mock(TelegramClient.class), json, props),
                new CriarPlanilhaGoogleFerramenta(google, json)
        };
        for (Ferramenta ferramenta : ferramentas) {
            var abas = ferramenta.esquemaEntrada().path("properties").path("abas");
            assertEquals("string", abas.path("items").path("properties").path("linhas")
                    .path("items").path("items").path("properties").path("valor")
                    .path("type").stringValue());
            assertEquals("nome", abas.path("items").path("properties").path("colunas")
                    .path("items").path("required").get(0).stringValue());
        }
    }

    @Test void pedidoTxtPercorreFilaUploadEResultadoPorId() throws Exception {
        var json = JsonMapper.builder().build();
        var pc = new PcService();
        var arquivos = new ArquivosPcService(pc);
        var props = new JarvisProperties("Jarvis", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token-de-teste", 123L),
                null, null, null, null, null, null, null, null, null, null);
        var telegram = mock(TelegramClient.class);
        var ferramenta = new CriarArquivoFerramenta(pc, arquivos, telegram, json, props);
        var entrada = json.readTree("{\"nome\":\"teste\",\"formato\":\"txt\",\"conteudo\":\"Olá\"}");
        pc.proximoComando(Duration.ofMillis(1), null); // sinal de que o agente está online
        try (var tarefas = Executors.newVirtualThreadPerTaskExecutor()) {
            var resposta = tarefas.submit(() -> ferramenta.executarDetalhado(entrada, new OuvinteProgresso() { }));
            var comando = pc.proximoComando(Duration.ofSeconds(2), null).orElseThrow();
            assertEquals("criar_arquivo", comando.acao());
            assertEquals("Olá", comando.dados().path("conteudo").stringValue());
            assertTrue(arquivos.guardar(comando.id(), "Olá".getBytes(StandardCharsets.UTF_8)));
            assertTrue(pc.concluir(new ResultadoPc(comando.id(), true, "teste.txt", null, null, true)));
            var resultado = resposta.get(3, TimeUnit.SECONDS);
            assertEquals("Arquivo criado: teste.txt.", resultado.texto());
            assertEquals(1, resultado.artefatos().size());
            assertEquals("Olá", java.nio.file.Files.readString(resultado.artefatos().get(0).arquivo()));
            verify(telegram).enviarDocumento(123L, "Olá".getBytes(StandardCharsets.UTF_8), "teste.txt");
        } finally { arquivos.fechar(); }
    }
}
