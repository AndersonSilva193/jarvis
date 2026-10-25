package dev.jarvis.diario;

import static dev.jarvis.diario.DiarioDeAcoesTest.props;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** A janela "desde o último briefing": o filtro de autônomas e o marcador que avança. */
class DiarioBriefingTest {

    @TempDir
    Path pasta;

    private final List<DiarioDeAcoes> criados = new ArrayList<>();

    @AfterEach
    void fechar() {
        criados.forEach(DiarioDeAcoes::fechar);
    }

    private DiarioDeAcoes subir(RelogioFalso relogio) {
        var novo = new DiarioDeAcoes(props(), JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    @DisplayName("conta só as ações autônomas: o que o dono aprovou ele já sabe")
    void contaSoAsAutonomas() {
        var diario = subir(new RelogioFalso("2026-10-15T12:00:00Z"));
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei outra.md", OrigemDaAcao.HUD);
        diario.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei \"stack\"", OrigemDaAcao.ANOTADOR);
        diario.registrar(TipoDeAcao.DESLIGAR_PC, "desligar o PC", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.APAGAR_ARQUIVO, "apagar velho.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        var contagem = diario.autonomasDesdeOUltimoBriefing();

        assertEquals(2, contagem.size(), "só os dois tipos autônomos entram");
        assertEquals(2, contagem.get(TipoDeAcao.EDICAO_ARQUIVO));
        assertEquals(1, contagem.get(TipoDeAcao.ANOTAR_MEMORIA));
        assertNull(contagem.get(TipoDeAcao.DESLIGAR_PC), "desligar foi aprovado: o dono já sabe");
        assertNull(contagem.get(TipoDeAcao.APAGAR_ARQUIVO), "apagar foi aprovado: o dono já sabe");
    }

    @Test
    @DisplayName("a segunda chamada vem vazia: o marcador avançou")
    void oMarcadorAvanca() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var diario = subir(relogio);
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        assertEquals(1, diario.autonomasDesdeOUltimoBriefing().size());
        diario.aguardar();

        assertTrue(diario.autonomasDesdeOUltimoBriefing().isEmpty(),
                "o briefing seguinte conta do zero, senão repetiria a mesma notícia todo dia");
        assertTrue(Files.exists(pasta.resolve(DiarioEmArquivo.MARCADOR)),
                "o marcador tem de estar em disco para sobreviver ao restart");
    }

    @Test
    @DisplayName("o que aconteceu depois do briefing entra na contagem seguinte")
    void oQueVeioDepoisEntraNaProxima() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var diario = subir(relogio);
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();
        diario.autonomasDesdeOUltimoBriefing();
        diario.aguardar();

        relogio.avancar(Duration.ofHours(2));
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        var contagem = diario.autonomasDesdeOUltimoBriefing();
        assertEquals(1, contagem.get(TipoDeAcao.EDICAO_ARQUIVO), "a edição é nova");
        assertNull(contagem.get(TipoDeAcao.CRIAR_ARQUIVO), "a criação já foi contada antes");
    }

    @Test
    @DisplayName("sem marcador a janela começa hoje, não nos 30 dias guardados")
    void semMarcadorAJanelaComecaHoje() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var antes = subir(relogio);
        antes.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei antigo.md", OrigemDaAcao.TELEGRAM);
        antes.aguardar();

        // Três dias depois, primeira execução da seção: nada de marcador em disco.
        relogio.avancar(Duration.ofDays(3));
        var depois = subir(relogio);
        depois.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei hoje.md", OrigemDaAcao.TELEGRAM);
        depois.aguardar();

        var contagem = depois.autonomasDesdeOUltimoBriefing();
        assertEquals(1, contagem.get(TipoDeAcao.EDICAO_ARQUIVO), "o de hoje entra");
        assertNull(contagem.get(TipoDeAcao.CRIAR_ARQUIVO),
                "uma instalação nova não despeja o histórico inteiro na primeira manhã");
    }

    @Test
    @DisplayName("marcador ilegível não derruba a seção: ela conta a partir de hoje")
    void marcadorIlegivelNaoDerruba() throws Exception {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var diario = subir(relogio);
        Files.writeString(pasta.resolve(DiarioEmArquivo.MARCADOR), "{nem json");
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        assertEquals(1, diario.autonomasDesdeOUltimoBriefing().get(TipoDeAcao.EDICAO_ARQUIVO),
                "marcador torto vale o mesmo que marcador ausente");
    }
}
