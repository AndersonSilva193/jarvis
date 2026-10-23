package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O parser de CUSTO_PRECOS: nenhum relógio, nenhum arquivo.
 *
 * Os números são de exemplo, escolhidos para a conta fechar exata em double. Não são os preços da
 * Anthropic, e não devem ser copiados para o .env.
 */
class TabelaDePrecosTest {

    @Test
    @DisplayName("dois modelos bem formados viram os quatro preços de cada um")
    void leDoisModelos() {
        var tabela = TabelaDePrecos.analisar(
                "modelo-a=1/5/1.25/0.1;modelo-b=3/15/3.75/0.3", 10);

        Preco a = tabela.precoDe("modelo-a");
        assertNotNull(a);
        assertEquals(1, a.entradaPorMilhao());
        assertEquals(5, a.saidaPorMilhao());
        assertEquals(1.25, a.cacheEscritaPorMilhao());
        assertEquals(0.1, a.cacheLeituraPorMilhao());
        assertEquals(15, tabela.precoDe("modelo-b").saidaPorMilhao());
        assertEquals(10, tabela.buscaPorMil());
    }

    @Test
    @DisplayName("espaço em volta e ponto e vírgula sobrando não atrapalham")
    void toleraEspacoEPontoEVirgulaFinal() {
        var tabela = TabelaDePrecos.analisar("  modelo-a = 1/5/1.25/0.1 ; ;", 0);
        assertNotNull(tabela.precoDe("modelo-a"));
        assertEquals(1, tabela.porModelo().size());
    }

    @Test
    @DisplayName("entrada torta é descartada e a boa do lado sobrevive")
    void entradaTortaNaoLevaAOutraJunto() {
        var tabela = TabelaDePrecos.analisar(
                "sem-igual;=1/5/1/1;poucos=1/5/1;demais=1/5/1/1/1;texto=1/x/1/1;"
                        + "negativo=1/-5/1/1;modelo-a=1/5/1.25/0.1", 0);

        assertEquals(1, tabela.porModelo().size(), "só a última entrada é válida");
        assertNotNull(tabela.precoDe("modelo-a"));
        assertNull(tabela.precoDe("poucos"));
        assertNull(tabela.precoDe("demais"));
        assertNull(tabela.precoDe("texto"));
        assertNull(tabela.precoDe("negativo"));
    }

    @Test
    @DisplayName("modelo repetido mantém o primeiro preço, como os sites monitorados")
    void repetidoMantemOPrimeiro() {
        var tabela = TabelaDePrecos.analisar("modelo-a=1/5/1/1;modelo-a=99/99/99/99", 0);
        assertEquals(1, tabela.precoDe("modelo-a").entradaPorMilhao());
    }

    @Test
    @DisplayName("sem configuração a tabela fica vazia, sem explodir")
    void semConfiguracao() {
        assertTrue(TabelaDePrecos.analisar(null, 0).porModelo().isEmpty());
        assertTrue(TabelaDePrecos.analisar("   ", 0).porModelo().isEmpty());
        assertNull(TabelaDePrecos.analisar("", 0).precoDe("qualquer"));
    }

    @Test
    @DisplayName("o nome do modelo casa exatamente: um apelido com data não herda o preço")
    void casamentoExato() {
        var tabela = TabelaDePrecos.analisar("claude-haiku-5-5=1/5/1/1", 0);
        assertNotNull(tabela.precoDe("claude-haiku-5-5"));
        assertNull(tabela.precoDe("claude-haiku-5-5-20260101"));
    }

    @Test
    @DisplayName("a conta: tokens por milhão, buscas por mil")
    void aConta() {
        var tabela = TabelaDePrecos.analisar("modelo-a=1/5/1.25/0.1", 10);

        assertEquals(1.0, tabela.custo("modelo-a", 1_000_000, 0, 0, 0, 0), 1e-9);
        assertEquals(5.0, tabela.custo("modelo-a", 0, 1_000_000, 0, 0, 0), 1e-9);
        assertEquals(1.25, tabela.custo("modelo-a", 0, 0, 1_000_000, 0, 0), 1e-9);
        assertEquals(0.1, tabela.custo("modelo-a", 0, 0, 0, 1_000_000, 0), 1e-9);
        // Dez buscas a US$ 10 por mil.
        assertEquals(0.1, tabela.custo("modelo-a", 0, 0, 0, 0, 10), 1e-9);
    }

    @Test
    @DisplayName("ler do cache custa menos que mandar os mesmos tokens como entrada crua")
    void cacheSaiMaisBarato() {
        var tabela = TabelaDePrecos.analisar("modelo-a=1/5/1.25/0.1", 0);
        double cru = tabela.custo("modelo-a", 500_000, 0, 0, 0, 0);
        double cacheado = tabela.custo("modelo-a", 0, 0, 0, 500_000, 0);
        assertTrue(cacheado < cru, "cache lido tem de sair mais barato que entrada crua");
    }

    @Test
    @DisplayName("modelo sem preço não zera a busca na web, que é cobrada à parte")
    void semPrecoAindaContaABusca() {
        var tabela = TabelaDePrecos.analisar("modelo-a=1/5/1.25/0.1", 10);
        assertNull(tabela.precoDe("outro"));
        assertEquals(0.02, tabela.custo("outro", 999_999, 999_999, 0, 0, 2), 1e-9);
    }

    @Test
    @DisplayName("100 mil tokens usam a faixa básica; acima disso a chamada inteira usa a longa")
    void faixaLongaValeParaChamadaInteira() {
        var tabela = TabelaDePrecos.analisar("haiku=0.10/0.50/0.125/0.01",
                "haiku@100000=0.50/2.50/0.625/0.05", 0);

        assertEquals(0.01, tabela.custo("haiku", 100_000, 0, 0, 0, 0), 1e-9);
        assertEquals(0.0500005, tabela.custo("haiku", 100_001, 0, 0, 0, 0), 1e-9);
        assertEquals(0.050003, tabela.custo("haiku", 100_001, 1, 0, 0, 0), 1e-9);
    }

    @Test
    @DisplayName("cache escrito e lido contam para o limiar e recebem o preço da faixa longa")
    void cacheContaParaOLimiar() {
        var tabela = TabelaDePrecos.analisar("haiku=0.10/0.50/0.125/0.01",
                "haiku@100000=0.50/2.50/0.625/0.05", 0);

        assertEquals(0.0125, tabela.custo("haiku", 0, 0, 100_000, 0, 0), 1e-9);
        assertEquals(0.06250005, tabela.custo("haiku", 0, 0, 100_000, 1, 0), 1e-9);
        assertEquals(0.00500005, tabela.custo("haiku", 0, 0, 0, 100_001, 0), 1e-9);
    }

    @Test
    @DisplayName("modelo sem segunda faixa continua com o preço básico")
    void segundoModeloSemFaixaLonga() {
        var tabela = TabelaDePrecos.analisar(
                "haiku=0.10/0.50/0.125/0.01;outro=1/5/1.25/0.1",
                "haiku@100000=0.50/2.50/0.625/0.05", 0);

        assertEquals(0.2, tabela.custo("outro", 200_000, 0, 0, 0, 0), 1e-9);
    }
}
