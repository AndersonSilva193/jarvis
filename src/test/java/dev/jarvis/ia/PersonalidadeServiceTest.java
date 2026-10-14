package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Testa a leitura do arquivo de personalidade: sem Spring e sem rede, só um arquivo temporário.
 */
class PersonalidadeServiceTest {

    @TempDir
    Path pasta;

    /**
     * Escreve o arquivo e empurra a data de modificação para a frente. O relógio do sistema de
     * arquivos tem granularidade grossa demais para dois writes seguidos aparecerem diferentes.
     */
    private Path escrever(String conteudo, int segundosNoFuturo) throws IOException {
        Path arquivo = pasta.resolve("personalidade.md");
        Files.writeString(arquivo, conteudo, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(arquivo,
                FileTime.from(Instant.now().plus(segundosNoFuturo, ChronoUnit.SECONDS)));
        return arquivo;
    }

    @Test
    @DisplayName("lê o arquivo e relê sozinho quando ele muda")
    void releQuandoMuda() throws Exception {
        Path arquivo = escrever("# Jarvis\n\nSeja formal, senhor.", 0);
        var servico = new PersonalidadeService(arquivo, "Anderson");

        assertEquals("# Jarvis\n\nSeja formal, senhor.", servico.texto());

        escrever("Agora seja informal.", 10);
        assertEquals("Agora seja informal.", servico.texto(),
                "mudou a data de modificação, então o serviço tinha que reler");
    }

    @Test
    @DisplayName("mesma data de modificação: não relê, devolve o que está em memória")
    void naoReleSemMudanca() throws Exception {
        Path arquivo = escrever("Texto original.", 0);
        var servico = new PersonalidadeService(arquivo, "Anderson");
        assertEquals("Texto original.", servico.texto());

        // Reescreve o conteúdo mantendo a data: o serviço não tem como perceber, e não deve reler.
        FileTime antes = Files.getLastModifiedTime(arquivo);
        Files.writeString(arquivo, "Texto trocado por baixo dos panos.", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(arquivo, antes);

        assertEquals("Texto original.", servico.texto());
    }

    @Test
    @DisplayName("arquivo que não existe cai na reserva, sem estourar")
    void arquivoAusenteUsaReserva() {
        var servico = new PersonalidadeService(pasta.resolve("nao-existe.md"), "Anderson");

        String texto = servico.texto();
        assertTrue(texto.contains("Jarvis"), texto);
        assertTrue(texto.contains("Anderson"), texto);
        assertEquals(texto, servico.texto(), "a reserva tem que ser estável entre chamadas");
    }

    @Test
    @DisplayName("arquivo em branco também cai na reserva")
    void arquivoVazioUsaReserva() throws Exception {
        Path arquivo = escrever("   \n\n  ", 0);
        var servico = new PersonalidadeService(arquivo, "Anderson");

        assertTrue(servico.texto().contains("assistente pessoal de Anderson"), servico.texto());
    }

    @Test
    @DisplayName("arquivo volta a existir depois da reserva e é lido de novo")
    void voltaDaReserva() throws Exception {
        Path arquivo = pasta.resolve("personalidade.md");
        var servico = new PersonalidadeService(arquivo, "Anderson");
        assertTrue(servico.texto().contains("Jarvis"));

        Files.writeString(arquivo, "Personalidade de verdade.", StandardCharsets.UTF_8);
        assertEquals("Personalidade de verdade.", servico.texto());
    }

    @Test
    @DisplayName("estimativa de tokens serve para decidir o cache, não para cobrar")
    void estimaTokens() {
        assertEquals(0, PersonalidadeService.estimarTokens(null));
        assertEquals(0, PersonalidadeService.estimarTokens(""));
        assertEquals(1000, PersonalidadeService.estimarTokens("a".repeat(3000)));
    }
}
