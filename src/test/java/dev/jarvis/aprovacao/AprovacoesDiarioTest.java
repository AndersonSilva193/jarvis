package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.Aprovacoes.Risco;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/**
 * O gancho central: toda ação aprovada que roda entra no diário, pelos dois caminhos de execução.
 *
 * Central de propósito — é isso que faz uma aprovação nova aparecer no diário sem ninguém lembrar
 * de registrá-la.
 */
class AprovacoesDiarioTest {

    @TempDir
    Path pasta;

    private final RelogioFalso relogio = new RelogioFalso("2026-10-15T15:00:00Z");
    private final AtomicInteger execucoes = new AtomicInteger();
    private DiarioDeAcoes diario;
    private Aprovacoes aprovacoes;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
    }

    private Aprovacoes comDiario() {
        diario = new DiarioDeAcoes(props(), JsonMapper.builder().build(), relogio, pasta);
        aprovacoes = new Aprovacoes(relogio, diario);
        return aprovacoes;
    }

    private void pedir(String tipo, String descricao) {
        aprovacoes.pedir(tipo, descricao, "Posso?", Risco.COMUM, 42, "pedido-1",
                () -> "feito " + execucoes.incrementAndGet());
    }

    private List<AcaoRegistrada> registradas() {
        diario.aguardar();
        return diario.doDia(LocalDate.parse("2026-10-15")).acoes();
    }

    @Test
    @DisplayName("o 'sim' em linguagem natural registra a ação aprovada")
    void simEmLinguagemNaturalRegistra() {
        comDiario();
        pedir("rotina", "Executar rotina backup");

        assertEquals("feito 1", aprovacoes.executarPendente(42, "pedido-2", "telegram").orElseThrow());

        var acoes = registradas();
        assertEquals(1, acoes.size());
        assertEquals(TipoDeAcao.ROTINA, acoes.getFirst().tipo());
        assertEquals("Executar rotina backup", acoes.getFirst().descricao(),
                "a descrição é a que a ferramenta montou em Java na hora do pedido");
        assertEquals(OrigemDaAcao.TELEGRAM, acoes.getFirst().origem());
    }

    @Test
    @DisplayName("a frase fixa 'confirmo' registra do mesmo jeito")
    void fraseFixaTambemRegistra() {
        comDiario();
        pedir("computer", "Tarefa no monitor principal: fechar o relatório");

        assertEquals("feito 1", aprovacoes.tratar("confirmo", 42, "pedido-2", "hud").orElseThrow());

        var acoes = registradas();
        assertEquals(1, acoes.size(), "os dois caminhos de execução passam pelo mesmo gancho");
        assertEquals(TipoDeAcao.COMPUTER_USE, acoes.getFirst().tipo());
        assertEquals(OrigemDaAcao.HUD, acoes.getFirst().origem());
    }

    @Test
    @DisplayName("apagar e desligar são tipos diferentes, apesar de a pendência de desligar vir do PC")
    void apagarEDesligarNaoSeConfundem() {
        comDiario();
        pedir("pc", "apagar velho.md");
        aprovacoes.executarPendente(42, "pedido-2", "telegram");

        aprovacoes.pedir(Aprovacoes.TIPO_PC_ENERGIA, "desligar o PC", "Posso?", Risco.CRITICO, 42,
                "pedido-3", () -> "desligando");
        aprovacoes.executarPendente(42, "pedido-4", "telegram");

        var acoes = registradas();
        assertEquals(TipoDeAcao.APAGAR_ARQUIVO, acoes.get(0).tipo());
        assertEquals(TipoDeAcao.DESLIGAR_PC, acoes.get(1).tipo(),
                "sem o tipo próprio, desligar entraria no diário como 'arquivo apagado'");
    }

    @Test
    @DisplayName("esquecer memória e tarefa de planilha também entram, de graça")
    void osOutrosTiposTambemEntram() {
        comDiario();
        pedir("memoria", "esquecer Angular no projeto X");
        aprovacoes.executarPendente(42, "pedido-2", "telegram");

        aprovacoes.pedir("tarefa_planilha", "atualizar a planilha de gastos", "Posso?",
                Risco.COMUM, 42, "pedido-3", () -> "agendada");
        aprovacoes.executarPendente(42, "pedido-4", "hud");

        var acoes = registradas();
        assertEquals(TipoDeAcao.ESQUECER_MEMORIA, acoes.get(0).tipo());
        assertEquals(TipoDeAcao.TAREFA_PLANILHA, acoes.get(1).tipo());
    }

    @Test
    @DisplayName("cancelar não registra nada: nada aconteceu")
    void cancelarNaoRegistra() {
        comDiario();
        pedir("rotina", "Executar rotina backup");

        assertEquals(Aprovacoes.Cancelamento.CANCELADA, aprovacoes.cancelarPendente(42, "pedido-2"));

        assertTrue(registradas().isEmpty(), "o diário registra o que foi feito, não o que foi pedido");
        assertEquals(0, execucoes.get());
    }

    @Test
    @DisplayName("tipo de pendência que o diário não conhece não impede o 'sim'")
    void tipoDesconhecidoNaoQuebraOSim() {
        comDiario();
        pedir("tipo_que_ainda_nao_existe", "fazer algo novo");

        assertEquals("feito 1", aprovacoes.executarPendente(42, "pedido-2", "telegram").orElseThrow(),
                "uma pendência nova não pode deixar de executar por causa do diário");
        assertTrue(registradas().isEmpty());
    }

    @Test
    @DisplayName("sem diário configurado a aprovação roda igual")
    void semDiarioFunciona() {
        var sozinha = new Aprovacoes(relogio);
        sozinha.pedir("rotina", "Executar rotina backup", "Posso?", Risco.COMUM, 42, "pedido-1",
                () -> "feito");

        assertEquals("feito", sozinha.executarPendente(42, "pedido-2", "telegram").orElseThrow());
    }

    @Test
    @DisplayName("diário que explode não derruba a ação já executada")
    void diarioQueExplodeNaoDerrubaAAcao() {
        diario = new DiarioDeAcoes(props(), JsonMapper.builder().build(), relogio, pasta) {
            @Override
            public void registrar(TipoDeAcao tipo, String descricao, OrigemDaAcao origem) {
                throw new IllegalStateException("volume cheio");
            }
        };
        aprovacoes = new Aprovacoes(relogio, diario);
        pedir("rotina", "Executar rotina backup");

        assertEquals("feito 1", aprovacoes.executarPendente(42, "pedido-2", "telegram").orElseThrow(),
                "a ação já aconteceu no PC; o diário é a testemunha dela, não o guarda");
    }
}
