package dev.jarvis.financas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jarvis.ferramentas.PlanilhaDoPc;

class LivroNoPcTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    private static final Instant MEIO_DIA = Instant.parse("2026-11-11T15:00:00Z");

    private static Lancamento gasto() {
        return new Lancamento("ab12cd34", Lancamento.Tipo.GASTO, 12000, "ia", "Claude Pro",
                LocalDate.of(2026, 11, 11), MEIO_DIA, "escritorio", null);
    }

    @Test
    void cabecalhoEArquivo() {
        assertEquals("financas.csv", LivroNoPc.ARQUIVO);
        assertEquals(List.of("Id", "Data", "Tipo", "Valor", "Categoria", "Descricao", "Origem", "RegistradoEm", "Remove"),
                LivroNoPc.CABECALHO);
    }

    @Test
    void linhaDeGasto() {
        assertEquals(List.of("ab12cd34", "11/11/2026", "gasto", "120,00", "ia", "Claude Pro", "escritorio",
                "11/11/2026 12:00", ""), LivroNoPc.linha(gasto(), SP));
        Lancamento grande = new Lancamento("x", Lancamento.Tipo.GANHO, 123456, "sites", "", LocalDate.of(2026, 11, 1),
                MEIO_DIA, "telegram", null);
        assertEquals("1234,56", LivroNoPc.linha(grande, SP).get(3));
        assertEquals("ganho", LivroNoPc.linha(grande, SP).get(2));
    }

    @Test
    void linhaDeRemocao() {
        List<String> linha = LivroNoPc.linhaDeRemocao("ff00ff00", gasto(), Instant.parse("2026-11-12T13:30:00Z"),
                "telegram", SP);
        assertEquals(List.of("ff00ff00", "11/11/2026", "remocao", "120,00", "ia", "Claude Pro", "telegram",
                "12/11/2026 10:30", "ab12cd34"), linha);
    }

    @Test
    void importarIgnoraLinhaInvalidaERespeitaRemocao() {
        var tabela = new PlanilhaDoPc.Tabela("financas.csv", LivroNoPc.CABECALHO, List.of(
                LivroNoPc.linha(gasto(), SP),
                List.of("cc", "10/11/2026", "ganho", "1500,00", "sites", "Site da barbearia", "escritorio", "10/11/2026 09:00", ""),
                List.of("dd", "data ruim", "gasto", "10,00", "x", "", "telegram", "", ""),
                List.of("ee", "09/11/2026", "gasto", "-5", "x", "", "telegram", "", ""),
                List.of("ff", "09/11/2026", "mistério", "5,00", "x", "", "telegram", "", ""),
                LivroNoPc.linhaDeRemocao("rr", gasto(), MEIO_DIA, "telegram", SP)), 6);
        List<Lancamento> lidos = LivroNoPc.importar(tabela, SP);
        assertEquals(2, lidos.size(), lidos.toString());
        Lancamento claude = lidos.stream().filter(l -> l.id().equals("ab12cd34")).findFirst().orElseThrow();
        assertFalse(claude.ativo(), "a linha de remoção tira o gasto das contas");
        Lancamento site = lidos.stream().filter(l -> l.id().equals("cc")).findFirst().orElseThrow();
        assertTrue(site.ativo());
        assertEquals(150000, site.centavos());
        assertEquals(Lancamento.Tipo.GANHO, site.tipo());
    }
}
