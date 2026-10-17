package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class MemoriaServiceTest {
    @TempDir Path pasta;
    private MemoriaService abrir() {
        return new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"),
                JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-10-17T02:30:00Z"), ZoneId.of("UTC")),
                ZoneId.of("America/Sao_Paulo"));
    }

    @Test void persisteNaVpsSemDependerDoPcEIsolaUsuariosProjetos() throws Exception {
        var s = abrir();
        var a = s.registrar(42, "projeto", "jarvis", "deploy", "VPS ligada", "declaracao_usuario", "telegram", "t1");
        s.registrar(42, "projeto", "outro", "deploy", "Outro ambiente", "declaracao_usuario", "hud", "h1");
        s.registrar(43, "projeto", "jarvis", "deploy", "Terceiro", "declaracao_usuario", "telegram", "t2");
        var reiniciado = abrir();
        assertEquals(a.id(), reiniciado.buscar(42, "VPS", "jarvis").getFirst().id());
        assertEquals(1, reiniciado.buscar(42, "deploy", "jarvis").size());
        assertEquals(0, reiniciado.buscar(43, "VPS", "jarvis").size());
        assertEquals("VPS ligada", reiniciado.ler(42, a.id()).conteudo());
        assertTrue(reiniciado.dataLocal(a.atualizadoEm()).contains("16/10/2026"));
    }

    @Test void corrigeSemDuplicarComRevisaoEIdempotencia() throws Exception {
        var s = abrir();
        var a = s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        assertEquals(a.id(), s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1").id());
        assertThrows(MemoriaService.Conflito.class, () -> s.registrar(42, "perfil", "", "cidade", "São Paulo", "declaracao_usuario", "telegram", "t2"));
        var b = s.atualizar(42, a.id(), 1, "São Paulo", "declaracao_usuario", "telegram", "t2");
        assertEquals(2, b.revisao());
        assertEquals(2, s.atualizar(42, a.id(), 1, "São Paulo", "declaracao_usuario", "telegram", "t2").revisao());
        assertThrows(MemoriaService.Conflito.class, () -> s.atualizar(42, a.id(), 1, "Rio", "declaracao_usuario", "telegram", "t3"));
        assertEquals(1, s.buscar(42, "cidade", "").size());
        assertTrue(Files.readString(pasta.resolve("memoria/usuarios/42/.historico/" + a.id() + "/1.md"),
                StandardCharsets.UTF_8).contains("Curitiba"));
    }

    @Test void esqueceEReaplicaMarcadorAposRestaurarBackupAntigo() throws Exception {
        var s = abrir();
        var a = s.registrar(42, "preferencias", "", "bebida", "Chá", "declaracao_usuario", "telegram", "t1");
        Path arquivo = pasta.resolve("memoria/usuarios/42/preferencias.md");
        String backupAntigo = Files.readString(arquivo);
        s.atualizar(42, a.id(), 1, "Café", "declaracao_usuario", "telegram", "t2");
        assertTrue(s.esquecer(42, a.id(), 2));
        assertNull(s.ler(42, a.id()));
        assertFalse(Files.exists(pasta.resolve("memoria/usuarios/42/.historico/" + a.id())));
        Files.writeString(arquivo, backupAntigo);
        var reiniciado = abrir();
        reiniciado.reaplicarExclusoes();
        assertNull(reiniciado.ler(42, a.id()));
        assertFalse(Files.readString(arquivo).contains("Chá"));
        assertTrue(Files.readString(pasta.resolve("exclusoes/usuarios/42/" + a.id() + ".md")).contains(a.id()));
        assertFalse(Files.readString(pasta.resolve("exclusoes/usuarios/42/" + a.id() + ".md")).contains("Chá"));
    }

    @Test void recusaSegredoCaminhoInvalidoESymlink() throws Exception {
        var s = abrir();
        assertThrows(IllegalArgumentException.class, () -> s.registrar(42, "projeto", "../outro", "x", "ok", "hipotese", "hud", "p"));
        assertThrows(IllegalArgumentException.class, () -> s.registrar(42, "perfil", "", "acesso", "senha = segredo", "declaracao_usuario", "hud", "p"));
        assertThrows(IllegalArgumentException.class, () -> s.registrar(42, "perfil", "", "x", "a".repeat(2_001), "declaracao_usuario", "hud", "p"));
        assertThrows(IllegalArgumentException.class, () -> s.ler(42, "../../etc/passwd"));
        Path usuarios = pasta.resolve("memoria/usuarios");
        Files.createDirectories(usuarios);
        try {
            Files.createSymbolicLink(usuarios.resolve("42"), pasta.resolve("fora"));
            assertThrows(java.io.IOException.class, () -> s.buscar(42, "x", ""));
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException ignorada) {
            // Algumas máquinas Windows não permitem symlinks ao usuário do teste.
        }
    }

    @Test void falhaDeGravacaoNaoConfirmaRegistro() throws Exception {
        var s = abrir();
        Path usuario = pasta.resolve("memoria/usuarios/42");
        Files.createDirectories(usuario);
        Files.createDirectory(usuario.resolve("perfil.md"));
        assertThrows(java.io.IOException.class, () -> s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1"));
    }

    @Test void concorrenciaPreservaUmaCorrecaoERecusaARevisaoAntiga() throws Exception {
        var s = abrir();
        var r = s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        var partida = new CountDownLatch(1);
        try (var tarefas = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = tarefas.submit(() -> { partida.await();
                return s.atualizar(42, r.id(), 1, "São Paulo", "declaracao_usuario", "telegram", "t2"); });
            var b = tarefas.submit(() -> { partida.await();
                return s.atualizar(42, r.id(), 1, "Rio", "declaracao_usuario", "telegram", "t3"); });
            partida.countDown();
            int sucessos = 0, conflitos = 0;
            for (var futuro : java.util.List.of(a, b)) {
                try { futuro.get(); sucessos++; }
                catch (java.util.concurrent.ExecutionException e) {
                    assertInstanceOf(MemoriaService.Conflito.class, e.getCause()); conflitos++;
                }
            }
            assertEquals(1, sucessos);
            assertEquals(1, conflitos);
            assertEquals(2, s.ler(42, r.id()).revisao());
        }
    }

    @Test void buscaLimitaResultados() throws Exception {
        var s = abrir();
        for (int i = 0; i < 8; i++) s.registrar(42, "perfil", "", "item " + i,
                "Dado " + i, "declaracao_usuario", "telegram", "t" + i);
        assertEquals(5, s.buscar(42, "item", "").size());
    }
}
