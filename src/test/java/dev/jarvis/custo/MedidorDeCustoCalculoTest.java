package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * O cálculo: somas por origem e por modelo, e o que acontece com modelo fora de CUSTO_PRECOS.
 *
 * Os preços são <b>de exemplo</b>, escolhidos para a conta fechar exata em double (um milhão de
 * tokens de entrada = US$ 1,00). Não são os preços da Anthropic.
 */
class MedidorDeCustoCalculoTest {

    private static final String PRECOS = "modelo-a=1/5/1.25/0.1";

    @TempDir
    Path pasta;

    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RelogioFalso relogio = new RelogioFalso();

    private final java.util.List<MedidorDeCusto> criados = new java.util.ArrayList<>();

    /** Fecha o executor de gravacao: sem isso o @TempDir nao consegue ser apagado no Windows. */
    @org.junit.jupiter.api.AfterEach
    void fechar() {
        criados.forEach(MedidorDeCusto::fechar);
    }

    private MedidorDeCusto medidor() {
        var novo = new MedidorDeCusto(props(PRECOS, ""), telegram, JsonMapper.builder().build(),
                relogio, pasta);
        criados.add(novo);
        return novo;
    }

    static JarvisProperties props(String precos, String teto) {
        return props(precos, teto, "");
    }

    static JarvisProperties props(String precos, String teto, String precosLongos) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 42L), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                new JarvisProperties.Custo(null, precos, "10", teto, precosLongos));
    }

    @Test
    @DisplayName("soma por origem e por modelo, cada um com o seu balde")
    void somaPorOrigemEPorModelo() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.registrar(Origem.BRIEFING, "modelo-a", 0, 1_000_000, 0, 0, 0);

        var hoje = medidor.hoje();
        assertEquals(6.0, hoje.totalUsd(), 1e-9);
        assertEquals(1.0, hoje.porOrigem().get(Origem.CONVERSA), 1e-9);
        assertEquals(5.0, hoje.porOrigem().get(Origem.BRIEFING), 1e-9);
        assertEquals(6.0, hoje.porModelo().get("modelo-a"), 1e-9);
    }

    @Test
    @DisplayName("as origens saem da mais cara para a mais barata")
    void origensOrdenadasPorCusto() {
        var medidor = medidor();
        medidor.registrar(Origem.ANOTADOR, "modelo-a", 100_000, 0, 0, 0, 0);
        medidor.registrar(Origem.CONVERSA, "modelo-a", 900_000, 0, 0, 0, 0);

        assertEquals(Origem.CONVERSA, medidor.hoje().porOrigem().keySet().iterator().next());
    }

    @Test
    @DisplayName("a taxa de acerto do cache sai dos três campos de entrada somados")
    void taxaDeAcertoDoCache() {
        var medidor = medidor();
        // 100 mil cheios, 100 mil escritos e 800 mil lidos: 80% da entrada veio do cache.
        medidor.registrar(Origem.CONVERSA, "modelo-a", 100_000, 0, 100_000, 800_000, 0);

        var hoje = medidor.hoje();
        assertEquals(100_000, hoje.tokensEntrada());
        assertEquals(100_000, hoje.cacheEscrito());
        assertEquals(800_000, hoje.cacheLido());
        assertEquals(1_000_000, hoje.tokensDeEntrada(),
                "o total do prompt é a soma dos três: input_tokens é só o resto não cacheado");
        assertEquals(80, hoje.percentualLidoDoCache());
    }

    @Test
    @DisplayName("sem token de entrada nenhum a taxa não existe, em vez de ser zero")
    void semEntradaNaoHaTaxa() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 0, 500, 0, 0, 0);

        assertEquals(-1, medidor.hoje().percentualLidoDoCache(),
                "\"cache: 0% lido\" num período sem entrada é ruído num texto lido em voz alta");
    }

    @Test
    @DisplayName("a taxa de acerto soma os dias do período, e não só o de hoje")
    void taxaSomaOsDiasDoPeriodo() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 0, 0, 0, 500_000, 0);
        relogio.avancar(java.time.Duration.ofDays(1));
        medidor.registrar(Origem.CONVERSA, "modelo-a", 500_000, 0, 0, 0, 0);

        var semana = medidor.periodo(7);
        assertEquals(500_000, semana.cacheLido());
        assertEquals(1_000_000, semana.tokensDeEntrada());
        assertEquals(50, semana.percentualLidoDoCache());
    }

    @Test
    @DisplayName("a busca na web entra uma vez, por mil")
    void buscaEntraUmaVez() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 0, 0, 0, 0, 3);

        var hoje = medidor.hoje();
        assertEquals(0.03, hoje.totalUsd(), 1e-9);
        assertEquals(3, hoje.buscasWeb());
    }

    @Test
    @DisplayName("modelo sem preço conta tokens, não conta dólar, e é nomeado no relatório")
    void modeloSemPrecoContaTokens() {
        var medidor = medidor();
        medidor.registrar(Origem.COMPUTER_USE, "gpt-sem-preco", 500_000, 10_000, 0, 0, 0);

        var hoje = medidor.hoje();
        assertEquals(0.0, hoje.totalUsd(), 1e-9, "sem preço não vira dólar");
        assertEquals(java.util.List.of("gpt-sem-preco"), hoje.modelosSemPreco());

        var serie = medidor.serieDiaria(1);
        assertEquals(1, serie.size());
        assertEquals(500_000, serie.get(0).tokensEntrada(), "os tokens continuam contados");
        assertTrue(serie.get(0).custoIncompleto(), "o dia tem de sair marcado como incompleto");
    }

    @Test
    @DisplayName("o mesmo modelo sem preço entra na lista uma vez só, por mais que repita")
    void semPrecoNaoRepeteNaLista() {
        var medidor = medidor();
        for (int i = 0; i < 5; i++) {
            medidor.registrar(Origem.CONVERSA, "gpt-sem-preco", 1_000, 100, 0, 0, 0);
        }
        assertEquals(1, medidor.hoje().modelosSemPreco().size());
    }

    @Test
    @DisplayName("um modelo com preço ao lado de um sem preço não contamina o total do outro")
    void misturaDePrecificadoENao() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.registrar(Origem.COMPUTER_USE, "gpt-sem-preco", 1_000_000, 0, 0, 0, 0);

        var hoje = medidor.hoje();
        assertEquals(1.0, hoje.totalUsd(), 1e-9);
        assertEquals(1.0, hoje.porModelo().get("modelo-a"), 1e-9);
        assertEquals(0.0, hoje.porModelo().get("gpt-sem-preco"), 1e-9);
        assertFalse(hoje.modelosSemPreco().isEmpty());
    }

    @Test
    @DisplayName("sem consumo nenhum o relatório vem vazio em vez de zerado")
    void diaSemConsumo() {
        assertTrue(medidor().hoje().vazio());
    }

    @Test
    @DisplayName("o medidor lê a faixa longa da configuração e cobra a chamada inteira nela")
    void usaFaixaLongaConfigurada() {
        var novo = new MedidorDeCusto(props("modelo-a=0.10/0.50/0.125/0.01", "",
                "modelo-a@100000=0.50/2.50/0.625/0.05"), telegram,
                JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);

        novo.registrar(Origem.CONVERSA, "modelo-a", 100_000, 1, 0, 1, 0);
        assertEquals(0.05000255, novo.hoje().totalUsd(), 1e-9);
    }
}
