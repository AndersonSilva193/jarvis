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
        return abrir(Clock.fixed(Instant.parse("2026-10-17T02:30:00Z"), ZoneId.of("UTC")));
    }

    private MemoriaService abrir(Clock relogio) {
        return new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"),
                JsonMapper.builder().build(), relogio, ZoneId.of("America/Sao_Paulo"));
    }

    /** Relógio que anda: sem criadoEm diferente não existe "o mais antigo" para cortar. */
    private static final class RelogioQueAnda extends Clock {
        private Instant agora = Instant.parse("2026-10-17T02:30:00Z");
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return agora; }
        void avancar() { agora = agora.plusSeconds(60); }
    }

    @Test void resumoTrazPerfilPreferenciasEProjetoCitado() throws Exception {
        var s = abrir();
        s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");
        s.registrar(42, "preferencias", "", "bebida", "Café sem açúcar", "declaracao_usuario", "telegram", "t2");
        s.registrar(42, "projeto", "jarvis", "deploy", "Roda na VPS", "declaracao_usuario", "telegram", "t3");
        s.registrar(42, "projeto", "outro", "servidor", "Roda no notebook", "declaracao_usuario", "telegram", "t4");
        s.registrar(42, "projeto", "cliente", "orçamento", "Cinco mil por mês", "declaracao_usuario", "telegram", "t5");
        s.registrar(42, "episodio", "", "almoço", "Comeu pizza na terça", "declaracao_usuario", "telegram", "t6");

        String resumo = s.resumoParaContexto(42, "como vai o deploy do JÁRVIS?");

        assertTrue(resumo.contains("- cidade: Curitiba"), resumo);
        assertTrue(resumo.contains("- bebida: Café sem açúcar"), resumo);
        assertTrue(resumo.contains("Roda na VPS"), "projeto citado pelo identificador entra: " + resumo);
        assertFalse(resumo.contains("notebook"), "projeto não citado fica fora: " + resumo);
        assertFalse(resumo.contains("Cinco mil"), "projeto não citado fica fora: " + resumo);
        assertFalse(resumo.contains("pizza"), "episódio é o que sobra para o buscar_memoria: " + resumo);
        assertFalse(resumo.contains("jarvis"), "sem id nem metadado no resumo: " + resumo);
        // O assunto também serve de gancho, e a comparação ignora acento e maiúscula.
        assertTrue(s.resumoParaContexto(42, "e o ORCAMENTO?").contains("Cinco mil por mês"));
    }

    @Test void resumoCortaOsMaisAntigosEAvisa() throws Exception {
        var relogio = new RelogioQueAnda();
        var s = abrir(relogio);
        String recheio = "x".repeat(200);
        for (int i = 1; i <= 12; i++) {
            s.registrar(42, "preferencias", "", "assunto " + i, "pref" + i + " " + recheio,
                    "declaracao_usuario", "telegram", "t" + i);
            relogio.avancar();
        }

        String resumo = s.resumoParaContexto(42, "qualquer coisa");

        assertTrue(resumo.length() <= MemoriaService.LIMITE_RESUMO,
                "o teto vale com o aviso dentro; veio " + resumo.length());
        assertTrue(resumo.contains("pref12 "), "o mais novo fica: " + resumo);
        assertFalse(resumo.contains("pref1 "), "o mais antigo é o primeiro a cair: " + resumo);
        assertTrue(resumo.endsWith("(há mais registros; use buscar_memoria)\n"),
                "cortar em silêncio faria o modelo jurar que o resto não existe: " + resumo);
    }

    @Test void resumoVazioSemUsuarioAutenticado() throws Exception {
        var s = abrir();
        s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "t1");

        assertEquals("", s.resumoParaContexto(0, "qual minha cidade?"));
        assertEquals("", s.resumoParaContexto(43, "qual minha cidade?"), "memória é por usuário");
        assertTrue(s.resumoParaContexto(42, "").contains("Curitiba"),
                "perfil não depende do que a mensagem diz");
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

    @Test void listaAgrupadaPaginaDeVinteESemIdNenhum() throws Exception {
        var s = abrir();
        s.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "telegram", "p1");
        s.registrar(42, "projeto", "agendabarber", "stack", "Angular no front",
                "declaracao_usuario", "telegram", "p2");
        s.registrar(42, "projeto", "outro", "banco", "Postgres", "declaracao_usuario", "telegram", "p3");
        for (int i = 1; i <= 22; i++) {
            s.registrar(42, "preferencias", "", "gosto " + i, "detalhe " + i,
                    "declaracao_usuario", "telegram", "g" + i);
        }

        var primeira = s.listar(42, "", 1);

        assertEquals(25, primeira.total(), "o total conta tudo, não só a página");
        assertEquals(MemoriaService.POR_PAGINA, primeira.fichas().size());
        assertEquals("perfil", primeira.fichas().getFirst().categoria(), "perfil vem primeiro");
        assertEquals(5, s.listar(42, "", 2).fichas().size());
        assertEquals(MemoriaService.LIMITE_PAGINAS, s.listar(42, "", 999).pagina(),
                "página absurda é limitada, não estoura");
        assertTrue(s.listar(42, "", 999).fichas().isEmpty());

        var doProjeto = s.listar(42, "agendabarber", 1);

        assertEquals(1, doProjeto.total(), "quem pergunta de um projeto não pediu o perfil inteiro");
        assertEquals("stack", doProjeto.fichas().getFirst().assunto());
        assertEquals("Angular no front", doProjeto.fichas().getFirst().trecho());
    }

    @Test void listaCortaConteudoLongoParaCaberNoOrcamento() throws Exception {
        var s = abrir();
        s.registrar(42, "perfil", "", "história", "x".repeat(500), "declaracao_usuario", "telegram", "p1");

        String trecho = s.listar(42, "", 1).fichas().getFirst().trecho();

        assertTrue(trecho.length() < 200, "20 linhas de 240 estourariam o orçamento: " + trecho.length());
        assertTrue(trecho.endsWith("…"));
    }

    @Test void assuntoRecusadoSeguraOAnotadorEPedidoExplicitoLibera() throws Exception {
        var s = abrir();
        var anotado = s.anotar(42, "perfil", "", "editor", "Usa o VS Code", "anotador:telegram", "a1");
        assertNotNull(anotado);

        s.desfazerUltimaEscrita(42, anotado.id());

        assertTrue(s.assuntoRecusado(42, "perfil", "", "editor"));
        assertThrows(MemoriaService.Recusado.class, () -> s.anotar(42, "perfil", "", "editor",
                "Usa o VS Code", "anotador:telegram", "a2"));
        assertTrue(s.listar(42, "", 1).fichas().isEmpty(), "o anotador não regravou nada");

        // Pedido explícito do dono mata a recusa: ele mudou de ideia, e a memória é dele.
        var pedido = s.registrar(42, "perfil", "", "editor", "Usa o VS Code",
                "declaracao_usuario", "usuario:telegram", "t1");

        assertFalse(s.assuntoRecusado(42, "perfil", "", "editor"));
        assertNotNull(s.ler(42, pedido.id()));
        assertNotNull(s.anotar(42, "perfil", "", "editor", "Usa o VS Code e o Zed",
                "anotador:telegram", "a3"), "liberado, o anotador volta a poder atualizar");
    }

    @Test void desfazerApagaAnotacaoNovaPeloCaminhoPermanente() throws Exception {
        var s = abrir();
        var anotado = s.anotar(42, "preferencias", "", "bebida", "Chá sem açúcar",
                "anotador:telegram", "a1");

        var desfeito = s.desfazerUltimaEscrita(42, anotado.id());

        assertFalse(desfeito.revertido(), "revisão 1 não tem versão anterior para onde voltar");
        assertEquals("bebida", desfeito.assunto());
        assertNull(s.ler(42, anotado.id()));
        // O mesmo marcador do esquecer: é ele que impede um backup antigo de ressuscitar o registro.
        assertTrue(Files.readString(pasta.resolve("exclusoes/usuarios/42/" + anotado.id() + ".md"))
                .contains(anotado.id()));
        assertTrue(s.assuntoRecusado(42, "preferencias", "", "bebida"));
        assertNull(s.desfazerUltimaEscrita(42, anotado.id()), "o que já saiu não tem o que desfazer");
    }

    @Test void desfazerVoltaAVersaoAnteriorQuandoAAnotacaoFoiAtualizacao() throws Exception {
        var s = abrir();
        var antes = s.registrar(42, "perfil", "", "editor", "Usa o VS Code",
                "declaracao_usuario", "usuario:telegram", "t1");
        assertTrue(s.anotar(42, "perfil", "", "editor", "Usa o Cursor", "anotador:telegram", "a1")
                .atualizado());
        assertEquals(2, s.ler(42, antes.id()).revisao());

        var desfeito = s.desfazerUltimaEscrita(42, antes.id());

        assertTrue(desfeito.revertido());
        assertEquals("Usa o VS Code", s.ler(42, antes.id()).conteudo(),
                "apagar levaria embora o fato que o dono nunca pediu para esquecer");
        assertEquals(1, s.ler(42, antes.id()).revisao());
        assertFalse(s.assuntoRecusado(42, "perfil", "", "editor"),
                "o dono recusou o conteúdo novo, não o assunto");
        assertFalse(Files.exists(pasta.resolve("memoria/usuarios/42/.historico/" + antes.id())),
                "a revisão guardada foi consumida pela volta");
    }

    @Test void buscaLimitaResultados() throws Exception {
        var s = abrir();
        for (int i = 0; i < 8; i++) s.registrar(42, "perfil", "", "item " + i,
                "Dado " + i, "declaracao_usuario", "telegram", "t" + i);
        assertEquals(5, s.buscar(42, "item", "").size());
    }
}
