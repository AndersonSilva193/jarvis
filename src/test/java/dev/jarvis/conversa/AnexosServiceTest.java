package dev.jarvis.conversa;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.ferramentas.ResultadoFerramenta.Artefato;

class AnexosServiceTest {
    @TempDir Path pasta;
    static class Relogio extends Clock {
        Instant agora = Instant.parse("2026-10-14T10:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zona) { return this; }
        public Instant instant() { return agora; }
    }
    @Test void copiaArtefatoEExpiraEmUmaHora() throws Exception {
        Relogio relogio = new Relogio();
        AnexosService anexos = new AnexosService(relogio);
        try {
            Path original = Files.writeString(pasta.resolve("original.txt"), "conteúdo");
            Anexo anexo = anexos.registrar(new Artefato(original, "../arquivo.txt", "text/plain"));
            Files.writeString(original, "alterado");
            assertEquals("conteúdo", new String(anexos.obter(anexo.id()).orElseThrow().bytes(), java.nio.charset.StandardCharsets.UTF_8));
            assertFalse(anexo.nome().contains("/"));
            assertTrue(anexos.obter("../../.env").isEmpty());
            relogio.agora = relogio.agora.plusSeconds(3600);
            assertTrue(anexos.obter(anexo.id()).isEmpty());
        } finally { anexos.fechar(); }
    }
    @Test void limitaTamanhoERejeitaLinksExecutaveis() throws Exception {
        AnexosService anexos = new AnexosService();
        try {
            Path grande = pasta.resolve("grande.bin");
            try (var arquivo = new java.io.RandomAccessFile(grande.toFile(), "rw")) {
                arquivo.setLength(AnexosService.LIMITE_ARQUIVO + 1);
            }
            assertThrows(java.io.IOException.class, () -> anexos.registrar(new Artefato(grande, "grande", null)));
            assertTrue(anexos.referencia("ruim", "javascript:alert(1)").isEmpty());
            assertTrue(anexos.referencia("ruim", "https://usuario:senha@exemplo.com").isEmpty());
            assertTrue(anexos.referencia("fonte", "https://example.org/artigo").isPresent());
        } finally { anexos.fechar(); }
    }

    @Test void limitaTotalECapacidadeVoltaAposExpirar() throws Exception {
        Relogio relogio = new Relogio();
        AnexosService anexos = new AnexosService(relogio);
        try {
            Path arquivo = pasta.resolve("dez-megas.bin");
            try (var saida = new java.io.RandomAccessFile(arquivo.toFile(), "rw")) {
                saida.setLength(AnexosService.LIMITE_ARQUIVO);
            }
            var artefato = new Artefato(arquivo, "arquivo", "application/octet-stream");
            for (int i = 0; i < 5; i++) anexos.registrar(artefato);
            assertThrows(java.io.IOException.class, () -> anexos.registrar(artefato));
            relogio.agora = relogio.agora.plusSeconds(3600);
            assertNotNull(anexos.registrar(artefato));
        } finally { anexos.fechar(); }
    }
}
