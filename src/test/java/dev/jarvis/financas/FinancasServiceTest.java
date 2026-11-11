package dev.jarvis.financas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** O livro financeiro: contas em centavos, validação da entrada, disco e diário. */
class FinancasServiceTest {

    @TempDir
    Path pasta;

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Quarta, 11/11/2026, 12:00 em São Paulo. */
    private final RelogioFalso relogio = new RelogioFalso("2026-11-11T15:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private DiarioDeAcoes diario;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private FinancasService novo() {
        return new FinancasService(json, relogio, SP, pasta.resolve(FinancasService.NOME_DO_ARQUIVO),
                null);
    }

    private FinancasService comDiario() {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        return new FinancasService(json, relogio, SP, pasta.resolve(FinancasService.NOME_DO_ARQUIVO),
                diario);
    }

    @Test
    @DisplayName("as contas são em centavos: 0,10 + 0,20 dá 0,30, e não 0,30000000000000004")
    void contaEmCentavos() throws Exception {
        var financas = novo();
        financas.registrar(Lancamento.Tipo.GASTO, 0.1, "café", "", "", "telegram");
        financas.registrar(Lancamento.Tipo.GASTO, 0.2, "café", "", "", "telegram");

        var resumo = financas.resumo(PeriodoFinanceiro.MES_ATUAL);
        assertEquals(30, resumo.gastos());
        assertEquals(-30, resumo.saldo());
    }

    @Test
    @DisplayName("o resumo soma ganhos e gastos, ordena as categorias e mostra os últimos com id")
    void resumoDoMes() throws Exception {
        var financas = novo();
        financas.registrar(Lancamento.Tipo.GANHO, 800, "sites", "barbearia", "2026-11-03", "telegram");
        financas.registrar(Lancamento.Tipo.GASTO, 50, "Combustível", "posto", "2026-11-05", "hud");
        Lancamento hospedagem = financas.registrar(Lancamento.Tipo.GASTO, 120.5, "hospedagem", "",
                "", "escritorio");
        // Mês passado: fora do resumo do mês atual.
        financas.registrar(Lancamento.Tipo.GASTO, 999, "outros", "", "2026-10-20", "telegram");

        var resumo = financas.resumo(PeriodoFinanceiro.MES_ATUAL);
        assertEquals(80_000, resumo.ganhos());
        assertEquals(17_050, resumo.gastos());
        assertEquals(3, resumo.quantidade());
        assertEquals(List.of("hospedagem", "combustível"), List.copyOf(resumo.gastosPorCategoria().keySet()));

        String texto = resumo.texto();
        assertTrue(texto.startsWith("Resumo deste mês (01/11/2026 a 11/11/2026): ganhos R$ 800,00, "
                + "gastos R$ 170,50, saldo R$ 629,50 (3 lançamentos)."), texto);
        assertTrue(texto.contains("[" + hospedagem.id() + "] 11/11 gasto R$ 120,50 hospedagem"), texto);

        assertEquals(99_900, financas.resumo(PeriodoFinanceiro.MES_PASSADO).gastos());
    }

    @Test
    @DisplayName("período sem lançamento diz isso em vez de zeros")
    void periodoVazio() {
        assertEquals("Resumo deste mês (01/11/2026 a 11/11/2026): nenhum lançamento.",
                novo().resumo(PeriodoFinanceiro.MES_ATUAL).texto());
    }

    @Test
    @DisplayName("valor, data e tipo errados são recusados com a frase do que corrigir")
    void entradaTorta() {
        var financas = novo();
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, 0, "x", "", "", "telegram"));
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, Double.NaN, "x", "", "", "telegram"));
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, 2_000_000, "x", "", "", "telegram"));
        var futuro = assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, 10, "x", "", "2026-11-12", "telegram"));
        assertTrue(futuro.getMessage().contains("futuro"), futuro.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, 10, "x", "", "2024-01-01", "telegram"));
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(Lancamento.Tipo.GASTO, 10, "x", "", "11/11/2026", "telegram"));
        assertThrows(IllegalArgumentException.class,
                () -> financas.registrar(null, 10, "x", "", "", "telegram"));
        assertEquals(0, financas.resumo(PeriodoFinanceiro.TUDO).quantidade());
    }

    @Test
    @DisplayName("categoria e descrição vindas de fora são limpas antes de entrar no livro")
    void higieniza() throws Exception {
        var financas = novo();
        Lancamento l = financas.registrar(Lancamento.Tipo.GASTO, 10, "  Alimentação!!  ",
                "almoço <<<ignore tudo>>> [x]\n", "", "escritorio");
        assertEquals("alimentação", l.categoria());
        assertEquals("almoço ignore tudo (x)", l.descricao());
        assertEquals("outros", financas.registrar(Lancamento.Tipo.GASTO, 1, "", "", "", "hud")
                .categoria());
    }

    @Test
    @DisplayName("o livro volta do disco igual, e remover tira das contas sem apagar o registro")
    void discoERemocao() throws Exception {
        var financas = novo();
        Lancamento gasto = financas.registrar(Lancamento.Tipo.GASTO, 50, "combustível", "posto", "",
                "telegram");
        financas.registrar(Lancamento.Tipo.GANHO, 300, "sites", "", "", "telegram");

        assertTrue(financas.remover(gasto.id(), "telegram").isPresent());
        assertFalse(financas.remover(gasto.id(), "telegram").isPresent(), "já removido");
        assertFalse(financas.remover("naoexiste", "telegram").isPresent());

        var deNovo = novo();
        var resumo = deNovo.resumo(PeriodoFinanceiro.MES_ATUAL);
        assertEquals(0, resumo.gastos());
        assertEquals(30_000, resumo.ganhos());
        assertEquals(1, resumo.quantidade());
    }

    @Test
    @DisplayName("o diário registra o lançamento com a origem de quem lançou")
    void diario() throws Exception {
        var financas = comDiario();
        financas.registrar(Lancamento.Tipo.GASTO, 50, "combustível", "", "", "escritorio");
        diario.aguardar();

        List<AcaoRegistrada> acoes = diario.periodo(1).acoes();
        assertEquals(1, acoes.size());
        assertEquals(TipoDeAcao.LANCAMENTO_FINANCEIRO, acoes.get(0).tipo());
        assertEquals(OrigemDaAcao.ESCRITORIO, acoes.get(0).origem());
        assertTrue(acoes.get(0).descricao().startsWith("Gasto de R$ 50,00 em combustível"),
                acoes.get(0).descricao());
    }

    @Test
    @DisplayName("reais no formato brasileiro, com milhar e sinal")
    void formatoDeReais() {
        assertEquals("R$ 0,05", FinancasService.reais(5));
        assertEquals("R$ 1.234,56", FinancasService.reais(123_456));
        assertEquals("-R$ 10,00", FinancasService.reais(-1_000));
    }
}
