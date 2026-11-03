package dev.jarvis.autonomia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** A ordem piso &gt; arquivo &gt; concessão &gt; padrão. */
class PoliticaComConcessoesTest {

    @TempDir
    Path pasta;

    private final RelogioFalso relogio = new RelogioFalso("2026-11-03T15:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();

    private Concessoes concessoes() {
        return new Concessoes(json, relogio, ZoneId.of("America/Sao_Paulo"),
                pasta.resolve(Concessoes.NOME_DO_ARQUIVO), null);
    }

    private PoliticaDeAutonomia politica(String autonomia, Concessoes concessoes) throws Exception {
        Path arquivo = pasta.resolve("autonomia.md");
        Files.writeString(arquivo, autonomia, StandardCharsets.UTF_8);
        return new PoliticaDeAutonomia(arquivo, concessoes);
    }

    private static void conceder(Concessoes c, AcaoAutonoma acao, String alvo) throws Exception {
        c.conceder(acao, alvo, Concessao.Escopo.PERMANENTE, null, null, Concessao.Origem.APRENDIDA,
                "três sim", 42, "telegram");
    }

    @Test
    @DisplayName("sem linha no arquivo, a concessão libera a ação e diz qual foi")
    void concessaoAntesDoPadrao() throws Exception {
        var c = concessoes();
        conceder(c, AcaoAutonoma.ROTINA_RODAR, "backup");
        var politica = politica("# nada\n", c);

        var decisao = politica.decidir(AcaoAutonoma.ROTINA_RODAR, "backup");
        assertEquals(Nivel.FAZ_E_AVISA, decisao.nivel());
        assertNotNull(decisao.concessao());
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "outra"));
        assertTrue(politica.resumo().contains("por permissão do dono: rodar a rotina backup (sem prazo)"),
                politica.resumo());
    }

    @Test
    @DisplayName("linha do arquivo vence a concessão, mesmo pergunta_antes, e a lista diz que não tem efeito")
    void arquivoVenceConcessao() throws Exception {
        var c = concessoes();
        conceder(c, AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null);
        conceder(c, AcaoAutonoma.PLANILHA_AGENDAR, null);
        var politica = politica("## pergunta_antes\n- arquivo.apagar_lote\n## proibido\n- planilha.agendar\n", c);

        var decisao = politica.decidir(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null);
        assertEquals(Nivel.PERGUNTA, decisao.nivel());
        assertNull(decisao.concessao());
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.PLANILHA_AGENDAR, null));
        String explicado = politica.explicar();
        assertTrue(explicado.contains("sem efeito: a configuração manda perguntar"), explicado);
        assertTrue(explicado.contains("sem efeito: a configuração proíbe"), explicado);
        assertFalse(politica.resumo().contains("por permissão do dono"), politica.resumo());
    }

    @Test
    @DisplayName("minhas_permissoes lista as concessões ativas com o prazo")
    void explicarListaComPrazo() throws Exception {
        var c = concessoes();
        c.conceder(AcaoAutonoma.ROTINA_RODAR, null, Concessao.Escopo.ATE,
                relogio.instant().plus(Duration.ofHours(8)).plusSeconds(59 * 60), null,
                Concessao.Origem.TEMPORARIA, "pode rodar", 42, "telegram");
        String explicado = politica("", c).explicar();
        assertTrue(explicado.contains("Permissões que você me deu: rodar rotina no PC, até hoje às 20:59"),
                explicado);
        assertTrue(explicado.contains("volta a perguntar"), explicado);
    }

    @Test
    @DisplayName("o piso fica acima de tudo: concessão nenhuma libera desligar ou reiniciar")
    void pisoAcimaDeTudo() throws Exception {
        var c = concessoes();
        var politica = politica("", c);
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PC_DESLIGAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PC_REINICIAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.COMPUTER_USE_INICIAR, null));
    }

    @Test
    @DisplayName("concessão de tarefa (7.5) fica guardada mas não libera nada numa conversa")
    void tarefaNaoValeNaConversa() throws Exception {
        var c = concessoes();
        c.conceder(AcaoAutonoma.ROTINA_RODAR, null, Concessao.Escopo.TAREFA, null, "t1",
                Concessao.Origem.PLANO, "plano aprovado", 42, "telegram");
        var politica = politica("", c);
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "backup"));
        assertEquals(1, c.ativas().size());
    }
}
