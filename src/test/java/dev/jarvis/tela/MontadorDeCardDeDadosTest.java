package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa o montador do gráfico cujos números são da casa. O que mais importa aqui é a origem:
 * "servidor" é a afirmação de que ninguém digitou aqueles valores, e só este caminho pode fazê-la.
 */
class MontadorDeCardDeDadosTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static DadosDoCard.Grafico.Serie serie(String nome, double... valores) {
        List<Double> lista = new ArrayList<>(valores.length);
        for (double valor : valores) {
            lista.add(valor);
        }
        return new DadosDoCard.Grafico.Serie(nome, lista);
    }

    private static CardTela card() {
        return MontadorDeCardDeDados.montar("A semana esquenta", "clima", "previsão de 3 dias",
                "A máxima sobe até quinta.", "linha", List.of("seg", "ter", "qua"),
                List.of(serie("máxima", 21.5, 23.4, 26.1)), "°C", null, "telegram", "23/10/2026 09:00");
    }

    @Test
    @DisplayName("o card sai como pesquisa no layout grafico, com origem 'servidor' nos números")
    void cardDoServidor() {
        CardTela card = card();

        assertEquals(CardTela.PESQUISA, card.tipo());
        assertEquals("A semana esquenta", card.titulo());
        assertEquals("telegram", card.origem(), "a origem do card é o canal do pedido");
        DadosDoCard.Pesquisa dados = (DadosDoCard.Pesquisa) card.dados();
        assertEquals(MontadorDeCardDePesquisa.GRAFICO, dados.layout());
        assertTrue(dados.itens().isEmpty(), "o layout grafico não tem itens");
        assertEquals("23/10/2026 09:00", dados.consultadoEm());
        assertEquals("servidor", dados.grafico().origem());
        assertEquals("°C", dados.grafico().unidade());
        assertEquals(List.of("seg", "ter", "qua"), dados.grafico().eixoX());
        assertEquals(26.1, dados.grafico().series().getFirst().valores().getLast(), 0.001);
    }

    @Test
    @DisplayName("não cobra fonte nem referência: o dado é da casa e o relógio é do servidor")
    void semFonteNemReferencia() {
        DadosDoCard.Pesquisa dados = (DadosDoCard.Pesquisa) card().dados();

        assertTrue(dados.fontes().isEmpty());
        assertEquals("", dados.referencia());
    }

    @Test
    @DisplayName("série de tamanho diferente do eixo é recusada: desenharia um gráfico que mente")
    void serieDesalinhada() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg", "ter", "qua"),
                        List.of(serie("máxima", 21.5, 23.4)), "°C", null));

        assertTrue(erro.getMessage().contains("2 valores para 3 pontos"), erro.getMessage());
    }

    @Test
    @DisplayName("NaN e infinito não entram: a linha sumiria no meio")
    void valorQueNaoExiste() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("barras", List.of("jan", "fev"),
                        List.of(serie("vendas", 10, Double.NaN)), "R$", null))
                .getMessage().contains("não é número finito"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("barras", List.of("jan", "fev"),
                        List.of(serie("vendas", 10, Double.POSITIVE_INFINITY)), "R$", null))
                .getMessage().contains("não é número finito"));
    }

    @Test
    @DisplayName("os limites da HUD valem igual para o número da casa")
    void limitesDaTela() {
        List<String> vinteECinco = new ArrayList<>();
        List<Double> valores = new ArrayList<>();
        for (int i = 0; i < MontadorDeCardDePesquisa.MAX_PONTOS_LINHA + 1; i++) {
            vinteECinco.add("p" + i);
            valores.add((double) i);
        }
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", vinteECinco,
                        List.of(new DadosDoCard.Grafico.Serie("n", valores)), "", null))
                .getMessage().contains("2 a 24 pontos"));

        // Uma linha precisa de dois pontos para existir; barras com um só é uma barra.
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg"),
                        List.of(serie("n", 1)), "", null)).getMessage().contains("2 a 24"));
        assertNull(MontadorDeCardDeDados.grafico("barras", List.of("seg"),
                List.of(serie("n", 1)), "", null).destaque());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg", "ter"),
                        List.of(serie("a", 1, 2), serie("b", 1, 2), serie("c", 1, 2),
                                serie("d", 1, 2)), "", null))
                .getMessage().contains("no máximo 3 séries"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("rosca", List.of("seg", "ter"),
                        List.of(serie("n", 1, 2)), "", null))
                .getMessage().contains("linha ou barras"));

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg", "ter"),
                        List.of(serie("n", 1, 2)), "unidade longa demais", null))
                .getMessage().contains("acima de 10 caracteres"));
    }

    @Test
    @DisplayName("destaque fora da faixa é recusado: a HUD não pode realçar o vazio")
    void destaqueForaDaFaixa() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg", "ter"),
                        List.of(serie("n", 1, 2)), "", new DadosDoCard.Grafico.Destaque(1, 0)))
                .getMessage().contains("série 1 não existe"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.grafico("linha", List.of("seg", "ter"),
                        List.of(serie("n", 1, 2)), "", new DadosDoCard.Grafico.Destaque(0, 5)))
                .getMessage().contains("ponto 5 não existe"));
    }

    @Test
    @DisplayName("falta de título, categoria, tema ou resumo é recusada nomeando o campo")
    void camposObrigatorios() {
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.montar("", "clima", "tema", "resumo", "linha",
                        List.of("a", "b"), List.of(serie("n", 1, 2)), "", null, "hud", "agora"))
                .getMessage().contains("'titulo'"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDeDados.montar("t", "clima", "tema", "   ", "linha",
                        List.of("a", "b"), List.of(serie("n", 1, 2)), "", null, "hud", "agora"))
                .getMessage().contains("'resumo'"));
    }

    @Test
    @DisplayName("o JSON que vai para a HUD leva origem 'servidor' em dados.grafico")
    void jsonDaHud() {
        JsonNode json = JSON.valueToTree(card());

        assertEquals("pesquisa", json.path("tipo").stringValue());
        assertEquals("grafico", json.path("dados").path("layout").stringValue());
        JsonNode grafico = json.path("dados").path("grafico");
        assertEquals("servidor", grafico.path("origem").stringValue());
        assertEquals("linha", grafico.path("tipo").stringValue());
        assertEquals("ter", grafico.path("eixoX").get(1).stringValue());
        assertEquals("máxima", grafico.path("series").get(0).path("nome").stringValue());
        assertEquals(3, grafico.path("series").get(0).path("valores").size());
    }
}
