package dev.jarvis.proativo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** O histórico agregado por dia: fuso, restart, expurgo e arquivo estragado. */
class HistoricoDeDisponibilidadeTest {

    @TempDir
    Path pasta;

    private final List<HistoricoDeDisponibilidade> criados = new ArrayList<>();

    /** Sem fechar o executor de gravação o @TempDir não consegue ser apagado no Windows. */
    @AfterEach
    void fechar() {
        criados.forEach(HistoricoDeDisponibilidade::fechar);
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Proativo("23:00-07:00", null, 5, ""));
    }

    /** Um "restart do container": histórico novo, mesma pasta, mesmo relógio. */
    private HistoricoDeDisponibilidade subir(RelogioFalso relogio) {
        var novo = new HistoricoDeDisponibilidade(props(), JsonMapper.builder().build(), relogio,
                pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    @DisplayName("o dia é o do fuso configurado, não o do UTC")
    void oDiaEhODoFuso() {
        // 02h30 UTC do dia 22 ainda é 23h30 do dia 21 em São Paulo.
        var historico = subir(new RelogioFalso("2026-10-22T02:30:00Z"));
        historico.registrarChecagem("loja", true);
        historico.aguardar();
        assertTrue(Files.exists(pasta.resolve("2026-10-21.json")),
                "o dia tem de ser o de quem olha o relógio da sala");
        assertFalse(Files.exists(pasta.resolve("2026-10-22.json")));
    }

    @Test
    @DisplayName("o restart soma em cima do que já estava no disco")
    void oRestartSomaAoQueExistia() {
        var relogio = new RelogioFalso("2026-10-26T15:00:00Z");
        var antes = subir(relogio);
        antes.registrarChecagem("loja", true);
        antes.registrarChecagem("loja", false);
        antes.registrarQueda("loja");
        antes.aguardar();

        var depois = subir(relogio);
        depois.registrarChecagem("loja", true);
        depois.aguardar();

        var loja = depois.serieDiaria(1).get(0).porSite().get("loja");
        assertEquals(3, loja.checagens());
        assertEquals(1, loja.falhas());
        assertEquals(1, loja.quedas());
    }

    @Test
    @DisplayName("dia sem arquivo é dia omitido, nunca dia com 100%")
    void diaSemArquivoNaoViraCemPorCento() {
        var relogio = new RelogioFalso("2026-10-26T15:00:00Z");
        var historico = subir(relogio);
        historico.registrarChecagem("loja", false);
        historico.aguardar();

        relogio.avancar(Duration.ofDays(2));
        historico.registrarChecagem("loja", true);
        historico.aguardar();

        var serie = historico.serieDiaria(7);
        assertEquals(2, serie.size(), "os dias em que o Jarvis estava desligado não entram");
        assertEquals("2026-10-26", serie.get(0).dia().toString());
        assertEquals("2026-10-28", serie.get(1).dia().toString());
    }

    @Test
    @DisplayName("vários sites convivem no mesmo dia")
    void variosSitesNoMesmoDia() {
        var historico = subir(new RelogioFalso("2026-10-26T15:00:00Z"));
        historico.registrarChecagem("loja", true);
        historico.registrarChecagem("api", false);
        historico.aguardar();
        var dia = historico.serieDiaria(1).get(0);
        assertEquals(1.0, dia.porSite().get("loja").disponibilidade(), 0.0001);
        assertEquals(0.0, dia.porSite().get("api").disponibilidade(), 0.0001);
    }

    @Test
    @DisplayName("arquivo estragado cai no backup .bak")
    void arquivoEstragadoCaiNoBackup() throws IOException {
        var relogio = new RelogioFalso("2026-10-26T15:00:00Z");
        var antes = subir(relogio);
        antes.registrarChecagem("loja", true);
        antes.aguardar();
        // Segunda gravação: só aí existe um .bak com a versão anterior.
        antes.registrarChecagem("loja", false);
        antes.aguardar();
        Files.writeString(pasta.resolve("2026-10-26.json"), "{isso não é json");

        var depois = subir(relogio);
        var loja = depois.serieDiaria(1).get(0).porSite().get("loja");
        assertEquals(1, loja.checagens(), "o .bak tinha a primeira checagem");
    }

    @Test
    @DisplayName("o expurgo apaga acima de 30 dias e não toca em arquivo alheio")
    void oExpurgoRespeitaONome() throws IOException {
        var relogio = new RelogioFalso("2026-10-26T15:00:00Z");
        var historico = subir(relogio);
        Files.createDirectories(pasta);
        Files.writeString(pasta.resolve("2026-01-05.json"), "{}");
        Files.writeString(pasta.resolve("leia-me.txt"), "não é meu");
        historico.registrarChecagem("loja", true);
        historico.aguardar();

        historico.virarODia();
        historico.aguardar();

        assertFalse(Files.exists(pasta.resolve("2026-01-05.json")), "dia vencido sai");
        assertTrue(Files.exists(pasta.resolve("leia-me.txt")),
                "arquivo que não casa o padrão do dia nunca é apagado");
        assertTrue(Files.exists(pasta.resolve("2026-10-26.json")), "hoje fica");
    }

    @Test
    @DisplayName("a série nunca passa dos 30 dias guardados, nem pedindo mais")
    void aSerieNaoPassaDoGuardado() {
        var historico = subir(new RelogioFalso("2026-10-26T15:00:00Z"));
        historico.registrarChecagem("loja", true);
        historico.aguardar();
        assertEquals(1, historico.serieDiaria(999).size());
        assertEquals(1, historico.serieDiaria(0).size());
    }
}
