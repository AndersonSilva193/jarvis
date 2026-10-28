package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A frase que o Jarvis fala quando perguntam quanto ele gastou.
 *
 * O medidor entra como dublê de propósito: o que se testa aqui é o texto, e quem prova a conta é o
 * {@code MedidorDeCustoCalculoTest}.
 */
class CustoDoJarvisFerramentaTest {

    /** O teto que a regra "devolva texto curto" vira aqui: tudo isso é lido em voz alta na HUD. */
    private static final int MAX_CARACTERES = 400;

    private static final LocalDate HOJE = LocalDate.parse("2026-10-22");

    private final JsonMapper json = JsonMapper.builder().build();
    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);
    private final CustoDoJarvisFerramenta ferramenta = new CustoDoJarvisFerramenta(medidor, json);

    private static Map<Origem, Double> porOrigem(Object... pares) {
        Map<Origem, Double> mapa = new LinkedHashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            mapa.put((Origem) pares[i], (Double) pares[i + 1]);
        }
        return mapa;
    }

    private static MedidorDeCusto.Relatorio relatorio(int dias, double total, long buscas,
            Map<Origem, Double> origens, Map<String, Double> modelos, List<String> semPreco,
            double teto, int percentual) {
        return new MedidorDeCusto.Relatorio(HOJE.minusDays(dias - 1L), HOJE, total, buscas,
                origens, modelos, semPreco, teto, percentual);
    }

    /** O mesmo retrato, mais os três campos de token de onde sai a taxa de acerto do cache. */
    private static MedidorDeCusto.Relatorio comCache(long entrada, long escrito, long lido) {
        return new MedidorDeCusto.Relatorio(HOJE, HOJE, 1.0, 0, porOrigem(Origem.CONVERSA, 1.0),
                Map.of("modelo-a", 1.0), List.of(), 0, 0, entrada, escrito, lido);
    }

    private String executar(String periodo) {
        ObjectNode entrada = json.createObjectNode();
        if (periodo != null) {
            entrada.put("periodo", periodo);
        }
        return ferramenta.executar(entrada);
    }

    @Test
    void mostraRankingMesmoQuandoNaoHouveCustoDeModelo() {
        when(medidor.periodo(1)).thenReturn(new MedidorDeCusto.Relatorio(HOJE, HOJE,
                0, 0, Map.of(), Map.of(), List.of(), 0, 0, 0, 0, 0,
                Map.of("clima", 3L, "criar_lembrete", 2L)));
        String texto = executar("hoje");
        assertTrue(texto.contains("clima 3"), texto);
        assertTrue(texto.contains("criar_lembrete 2"), texto);
    }

    @Test
    @DisplayName("diz quanto da entrada foi lido do cache")
    void dizATaxaDeAcertoDoCache() {
        when(medidor.periodo(1)).thenReturn(comCache(100_000, 100_000, 800_000));

        String resposta = executar("hoje");

        assertTrue(resposta.contains("Cache: 80% lido."), resposta);
        assertTrue(resposta.length() <= MAX_CARACTERES, resposta);
    }

    @Test
    @DisplayName("sem token de entrada no período, a frase do cache não sai")
    void semEntradaNaoFalaDeCache() {
        when(medidor.periodo(1)).thenReturn(comCache(0, 0, 0));

        String resposta = executar("hoje");

        assertFalse(resposta.contains("Cache"),
                "isto é lido em voz alta; \"0% lido\" num período sem entrada é ruído: " + resposta);
    }

    @Test
    @DisplayName("o esquema oferece três períodos fechados e não exige nenhum")
    void esquemaFechadoESemObrigatorio() {
        assertEquals("custo_do_jarvis", ferramenta.nome());

        ObjectNode esquema = ferramenta.esquemaEntrada();
        var opcoes = esquema.path("properties").path("periodo").path("enum");
        assertEquals(3, opcoes.size());
        assertEquals("[\"hoje\",\"semana\",\"mes\"]", opcoes.toString());
        assertTrue(esquema.path("required").isMissingNode(), "perguntar sem período tem de valer");
    }

    @Test
    @DisplayName("sem período, responde sobre hoje, curto e com vírgula decimal")
    void semPeriodoRespondeHoje() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.42, 0,
                porOrigem(Origem.CONVERSA, 1.20, Origem.BRIEFING, 0.22),
                Map.of("modelo-a", 1.42), List.of(), 0, 0));

        String texto = executar(null);
        assertTrue(texto.startsWith("Hoje: US$ 1,42."), texto);
        assertTrue(texto.contains("Por origem: conversa 1,20; briefing 0,22."), texto);
        assertTrue(texto.contains("Por modelo: modelo-a 1,42."), texto);
        assertTrue(texto.length() < MAX_CARACTERES, "resposta longa demais: " + texto.length());
    }

    @Test
    @DisplayName("na semana, diz o intervalo e a média por dia")
    void semanaTrazMedia() {
        when(medidor.periodo(7)).thenReturn(relatorio(7, 7.00, 0,
                porOrigem(Origem.CONVERSA, 7.00), Map.of("modelo-a", 7.00), List.of(), 0, 0));

        String texto = executar("semana");
        assertTrue(texto.startsWith("Últimos 7 dias: US$ 7,00 (média US$ 1,00/dia)."), texto);
        assertTrue(texto.length() < MAX_CARACTERES);
    }

    @Test
    @DisplayName("o mês são 30 dias")
    void mesSaoTrintaDias() {
        when(medidor.periodo(30)).thenReturn(relatorio(30, 30.00, 0,
                porOrigem(Origem.CONVERSA, 30.00), Map.of("modelo-a", 30.00), List.of(), 0, 0));
        assertTrue(executar("mes").startsWith("Últimos 30 dias: US$ 30,00"));
    }

    @Test
    @DisplayName("dia sem gasto nenhum diz isso em vez de recitar zeros")
    void diaZerado() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 0, 0, porOrigem(), Map.of(),
                List.of(), 0, 0));
        assertEquals("Hoje ainda não gastei nada com modelo.", executar(null));
    }

    @Test
    @DisplayName("com teto configurado, a resposta de hoje fecha com quanto dele já foi")
    void mostraOTeto() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.00, 0,
                porOrigem(Origem.CONVERSA, 1.00), Map.of("modelo-a", 1.00), List.of(), 2.00, 50));
        assertTrue(executar(null).contains("Teto diário US$ 2,00 — 50% usado."));
    }

    @Test
    @DisplayName("na semana o teto não aparece: ele é diário, e comparar seria enganoso")
    void tetoNaoApareceNoPeriodoLongo() {
        when(medidor.periodo(7)).thenReturn(relatorio(7, 7.00, 0,
                porOrigem(Origem.CONVERSA, 7.00), Map.of("modelo-a", 7.00), List.of(), 2.00, 0));
        assertFalse(executar("semana").contains("Teto"));
    }

    @Test
    @DisplayName("modelo fora de CUSTO_PRECOS é nomeado, para o dono saber o que corrigir")
    void avisaModeloSemPreco() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 0, 0,
                porOrigem(Origem.COMPUTER_USE, 0.0), Map.of("gpt-sem-preco", 0.0),
                List.of("gpt-sem-preco"), 0, 0));

        String texto = executar(null);
        assertTrue(texto.contains("Sem preço configurado em CUSTO_PRECOS para gpt-sem-preco"), texto);
    }

    @Test
    @DisplayName("balde zerado fica de fora: ele só gastaria token para dizer 'zero'")
    void baldeZeradoNaoEntra() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.00, 0,
                porOrigem(Origem.CONVERSA, 1.00, Origem.ANOTADOR, 0.0),
                Map.of("modelo-a", 1.00), List.of(), 0, 0));
        assertFalse(executar(null).contains("anotador"));
    }

    @Test
    @DisplayName("as buscas na web entram na frase, no singular e no plural")
    void contaAsBuscas() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.00, 1,
                porOrigem(Origem.CONVERSA, 1.00), Map.of("modelo-a", 1.00), List.of(), 0, 0));
        assertTrue(executar(null).contains("1 busca na web."));

        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.00, 3,
                porOrigem(Origem.CONVERSA, 1.00), Map.of("modelo-a", 1.00), List.of(), 0, 0));
        assertTrue(executar(null).contains("3 buscas na web."));
    }

    @Test
    @DisplayName("gasto abaixo de um centavo não vira 'US$ 0,00', que soaria como de graça")
    void centavoDeMenos() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 0.001, 0,
                porOrigem(Origem.ANOTADOR, 0.001), Map.of("modelo-a", 0.001), List.of(), 0, 0));
        assertTrue(executar(null).startsWith("Hoje: menos de US$ 0,01."), executar(null));
    }

    @Test
    @DisplayName("muitas origens não viram uma lista infinita para ler em voz alta")
    void listaNaoCresceSemLimite() {
        Map<Origem, Double> todas = new LinkedHashMap<>();
        for (Origem origem : Origem.values()) {
            todas.put(origem, 1.00);
        }
        when(medidor.periodo(1)).thenReturn(relatorio(1, 6.00, 0, todas,
                Map.of("modelo-a", 6.00), List.of(), 0, 0));

        String texto = executar(null);
        // Quatro origens por extenso, o resto contado: a conta acompanha a Origem que entrar.
        assertTrue(texto.contains("e mais " + (Origem.values().length - 4)), texto);
        assertTrue(texto.length() < MAX_CARACTERES);
    }

    @Test
    @DisplayName("período desconhecido cai em hoje, em vez de falhar")
    void periodoDesconhecidoCaiEmHoje() {
        when(medidor.periodo(1)).thenReturn(relatorio(1, 1.00, 0,
                porOrigem(Origem.CONVERSA, 1.00), Map.of("modelo-a", 1.00), List.of(), 0, 0));
        assertTrue(executar("ontem").startsWith("Hoje: "));
    }
}
