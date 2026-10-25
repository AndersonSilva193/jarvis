package dev.jarvis.diario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.util.ArquivoAtomico;
import tools.jackson.databind.json.JsonMapper;

/** A virada do dia no fuso, a leitura depois do restart, o teto por dia e o expurgo dos 30 dias. */
class DiarioDeAcoesTest {

    @TempDir
    Path pasta;

    private final List<DiarioDeAcoes> criados = new ArrayList<>();

    /** Fecha o executor de gravacao: sem isso o @TempDir nao consegue ser apagado no Windows. */
    @AfterEach
    void fechar() {
        criados.forEach(DiarioDeAcoes::fechar);
    }

    static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
    }

    /** Um "restart do container": diário novo, mesma pasta, mesmo relógio. */
    private DiarioDeAcoes subir(RelogioFalso relogio) {
        var novo = new DiarioDeAcoes(props(), JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    @DisplayName("o dia é o do fuso configurado, não o do UTC")
    void oDiaEhODoFuso() {
        // 02:30 UTC de 22/10 ainda é 23:30 de 21/10 em São Paulo (UTC-3).
        var diario = subir(new RelogioFalso("2026-10-22T02:30:00Z"));
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        assertTrue(Files.exists(pasta.resolve("2026-10-21.json")),
                "a ação pertence ao dia 21 em São Paulo, não ao 22 do UTC");
        assertFalse(Files.exists(pasta.resolve("2026-10-22.json")));
        assertEquals(1, diario.doDia(LocalDate.parse("2026-10-21")).acoes().size());
    }

    @Test
    @DisplayName("depois de um restart as ações do dia voltam do disco")
    void oRestartNaoPerdeODia() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var antes = subir(relogio);
        antes.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei relatorio.md", OrigemDaAcao.HUD);
        antes.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei \"stack do projeto\"",
                OrigemDaAcao.ANOTADOR);
        antes.aguardar();

        var depois = subir(relogio);
        var dia = depois.doDia(LocalDate.parse("2026-10-15"));

        assertEquals(2, dia.acoes().size(), "o dia inteiro tem de voltar do disco");
        assertEquals(TipoDeAcao.CRIAR_ARQUIVO, dia.acoes().getFirst().tipo(),
                "o diário é cronológico: a primeira gravada é a primeira lida");
        assertEquals(OrigemDaAcao.ANOTADOR, dia.acoes().get(1).origem(),
                "a origem tem de sobreviver à ida e volta ao JSON");
    }

    @Test
    @DisplayName("dia corrompido é recuperado do .bak em vez de derrubar a leitura")
    void diaCorrompidoCaiNoBackup() throws Exception {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var antes = subir(relogio);
        antes.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        antes.aguardar();
        // A segunda gravação é que cria o .bak com a versão anterior.
        antes.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei outra.md", OrigemDaAcao.TELEGRAM);
        antes.aguardar();

        Path doDia = pasta.resolve("2026-10-15.json");
        assertTrue(Files.exists(ArquivoAtomico.backupDe(doDia)), "o .bak tem de existir");
        Files.writeString(doDia, "{isso não é json");

        var depois = subir(relogio);
        assertEquals(1, depois.doDia(LocalDate.parse("2026-10-15")).acoes().size(),
                "o principal ilegível cai no .bak, que tinha uma ação só");
    }

    @Test
    @DisplayName("o dia para de crescer no teto e passa a contar o que recusou")
    void oDiaTemTeto() {
        var diario = subir(new RelogioFalso("2026-10-15T12:00:00Z"));
        for (int i = 0; i < DiaDoDiario.MAX_ACOES + 3; i++) {
            diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei arquivo-" + i + ".md",
                    OrigemDaAcao.TELEGRAM);
        }
        diario.aguardar();

        var dia = diario.doDia(LocalDate.parse("2026-10-15"));
        assertEquals(DiaDoDiario.MAX_ACOES, dia.acoes().size(), "o teto é o teto");
        assertEquals(3, dia.descartadas(),
                "o dia truncado tem de se declarar truncado, senão a lista mente por omissão");
    }

    @Test
    @DisplayName("o contador de descartadas sobrevive ao restart")
    void oTruncamentoSobreviveAoRestart() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var antes = subir(relogio);
        for (int i = 0; i < DiaDoDiario.MAX_ACOES + 2; i++) {
            antes.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei arquivo-" + i + ".md",
                    OrigemDaAcao.TELEGRAM);
        }
        antes.aguardar();

        var depois = subir(relogio);
        assertEquals(2, depois.doDia(LocalDate.parse("2026-10-15")).descartadas(),
                "relido do JSON, o dia continua dizendo que está incompleto");
    }

    @Test
    @DisplayName("o expurgo leva o que passou de 30 dias e não toca em arquivo alheio")
    void expurgoRespeitaOPrazoEOsVizinhos() throws Exception {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var diario = subir(relogio);

        ArquivoAtomico.gravar(pasta.resolve("2026-08-01.json"), "{}");
        ArquivoAtomico.gravar(pasta.resolve("2026-08-01.json"), "{}");
        ArquivoAtomico.gravar(pasta.resolve("2026-10-14.json"), "{}");
        Files.writeString(pasta.resolve("anotacoes.txt"), "arquivo de outra pessoa");

        diario.virarODia();
        diario.aguardar();

        assertFalse(Files.exists(pasta.resolve("2026-08-01.json")), "agosto passou dos 30 dias");
        assertFalse(Files.exists(ArquivoAtomico.backupDe(pasta.resolve("2026-08-01.json"))),
                "o .bak do dia vencido sai junto, senão fica no volume para sempre");
        assertTrue(Files.exists(pasta.resolve("2026-10-14.json")), "ontem continua dentro do prazo");
        assertTrue(Files.exists(pasta.resolve("anotacoes.txt")),
                "arquivo que não casa o padrão do dia não é nosso para apagar");
    }

    @Test
    @DisplayName("o período soma os dias e omite o dia em que o Jarvis estava desligado")
    void periodoSomaOsDiasComMovimento() {
        var relogio = new RelogioFalso("2026-10-15T12:00:00Z");
        var diario = subir(relogio);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        relogio.avancar(Duration.ofDays(2));
        diario.registrar(TipoDeAcao.ROTINA, "Executar rotina backup", OrigemDaAcao.HUD);
        diario.aguardar();

        assertEquals(2, diario.periodo(7).acoes().size(),
                "os dois dias com movimento entram; o do meio simplesmente não existe");
        assertFalse(Files.exists(pasta.resolve("2026-10-16.json")),
                "dia sem ação não vira arquivo vazio");
    }
}
