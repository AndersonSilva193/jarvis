package dev.jarvis.financas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.escritorio.EscritaNoPc.Estado;
import dev.jarvis.escritorio.EscritaNoPc.Resposta;
import dev.jarvis.escritorio.FilaDoPc;
import dev.jarvis.escritorio.PedidoDeEscrita;
import dev.jarvis.ferramentas.PlanilhaDoPc;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** O livro financeiro gravado no financas.csv da pasta do PC, pela fila. */
class FinancasNoPcTest {

    @TempDir Path pasta;

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private final RelogioFalso relogio = new RelogioFalso("2026-11-11T15:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<PedidoDeEscrita> enviados = new ArrayList<>();
    private Estado estadoDoPc = Estado.GRAVADO;
    private Supplier<Optional<PlanilhaDoPc.Tabela>> livroDoPc = Optional::empty;
    private int leiturasDoPc;

    private FilaDoPc fila() {
        return new FilaDoPc(pedido -> { enviados.add(pedido); return new Resposta(estadoDoPc, ""); },
                pasta.resolve("pendentes-pc.json"), json, texto -> { }, Runnable::run);
    }

    private FinancasService financas(FilaDoPc fila) {
        return new FinancasService(json, relogio, SP, pasta.resolve(FinancasService.NOME_DO_ARQUIVO), null,
                fila, () -> { leiturasDoPc++; return livroDoPc.get(); }, Runnable::run);
    }

    private static List<String> linha(JsonNode linha) {
        List<String> celulas = new ArrayList<>();
        for (JsonNode celula : linha) celulas.add(celula.asString());
        return celulas;
    }

    @Test
    void registrarComPcGravaNoLivroDoPc() throws Exception {
        var financas = financas(fila());
        Registrado r = financas.registrarNoLivro(Lancamento.Tipo.GASTO, 120, "ia", "Claude Pro", "", "escritorio");
        assertTrue(r.gravadoNoPc());
        assertEquals(1, enviados.size());
        PedidoDeEscrita pedido = enviados.get(0);
        assertEquals("acrescentar_linhas", pedido.acao());
        assertEquals("financas.csv", pedido.arquivo());
        assertEquals(LivroNoPc.CABECALHO, linha(pedido.dados().path("cabecalho")));
        assertEquals(LivroNoPc.linha(r.lancamento(), SP), linha(pedido.dados().path("linhas").get(0)));
        assertEquals("Gasto de R$ 120,00 em ia (Claude Pro), 11/11/2026. Id " + r.lancamento().id() + ".",
                FinancasService.confirmacao(r));
    }

    @Test
    void registrarComPcForaFicaNaFilaEConfirmaComAviso() throws Exception {
        estadoDoPc = Estado.PC_OFFLINE;
        FilaDoPc fila = fila();
        var financas = financas(fila);
        Registrado r = financas.registrarNoLivro(Lancamento.Tipo.GASTO, 120, "ia", "Claude Pro", "", "escritorio");
        assertFalse(r.gravadoNoPc());
        assertTrue(FinancasService.confirmacao(r).contains("quando o PC voltar"), FinancasService.confirmacao(r));
        assertEquals(1, fila.pendentes());
        assertEquals(12000, financas.resumo(PeriodoFinanceiro.MES_ATUAL).gastos(), "já entra nas contas");
    }

    @Test
    void travadoAvisaQueOArquivoEstaAberto() throws Exception {
        estadoDoPc = Estado.ARQUIVO_TRAVADO;
        Registrado r = financas(fila()).registrarNoLivro(Lancamento.Tipo.GANHO, 50, "sites", "", "", "telegram");
        assertTrue(FinancasService.confirmacao(r).contains("aberto no PC"), FinancasService.confirmacao(r));
    }

    @Test
    void removerAcrescentaLinhaDeRemocaoNuncaAlteraLinha() throws Exception {
        var financas = financas(fila());
        Lancamento l = financas.registrar(Lancamento.Tipo.GASTO, 30, "café", "", "", "telegram");
        Optional<Registrado> removido = financas.removerDoLivro(l.id(), "telegram");
        assertTrue(removido.isPresent());
        assertEquals(2, enviados.size());
        assertTrue(enviados.stream().allMatch(p -> p.acao().equals("acrescentar_linhas")));
        List<String> remocao = linha(enviados.get(1).dados().path("linhas").get(0));
        assertEquals("remocao", remocao.get(2));
        assertEquals(l.id(), remocao.get(8));
    }

    @Test
    void migracaoEnfileiraLancamentosAntigosUmaVez() throws Exception {
        var antes = financas(null);
        antes.registrar(Lancamento.Tipo.GASTO, 10, "café", "", "", "telegram");
        Lancamento removido = antes.registrar(Lancamento.Tipo.GASTO, 20, "lanche", "", "", "telegram");
        antes.remover(removido.id(), "telegram");

        FilaDoPc fila = fila();
        financas(fila).migrarParaOPc();
        assertEquals(1, enviados.size());
        assertEquals(3, enviados.get(0).dados().path("linhas").size(), "2 lançamentos + a remoção");
        assertTrue(Files.exists(pasta.resolve("financas-pc-migrado")));

        financas(fila).migrarParaOPc();
        assertEquals(1, enviados.size(), "a segunda partida não manda de novo");
    }

    @Test
    void copiaVaziaImportaDoPcNoPcVoltou() throws Exception {
        livroDoPc = () -> Optional.of(new PlanilhaDoPc.Tabela("financas.csv", LivroNoPc.CABECALHO, List.of(
                List.of("aa", "05/11/2026", "gasto", "100,00", "vps", "", "telegram", "05/11/2026 10:00", ""),
                List.of("bb", "06/11/2026", "gasto", "20,00", "ia", "", "telegram", "06/11/2026 10:00", "")), 2));
        var financas = financas(fila());
        financas.pcVoltou(null);
        assertEquals(12000, financas.resumo(PeriodoFinanceiro.MES_ATUAL).gastos());
        assertEquals(1, leiturasDoPc);

        var outra = financas(fila());
        outra.pcVoltou(null);
        assertEquals(1, leiturasDoPc, "cópia com lançamentos não relê o PC");
    }

    @Test
    void semPcNadaMuda() throws Exception {
        var financas = financas(null);
        Registrado r = financas.registrarNoLivro(Lancamento.Tipo.GASTO, 5, "café", "", "", "telegram");
        assertTrue(r.gravadoNoPc());
        assertEquals(LocalDate.of(2026, 11, 11), r.lancamento().data());
    }
}
