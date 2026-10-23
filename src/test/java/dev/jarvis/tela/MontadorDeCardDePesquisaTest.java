package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Testa a validação do mostrar_na_tela. Tudo aqui vem do modelo, que erra formato, estoura
 * tamanho e às vezes escreve uma URL que não é URL — e o que passar daqui vai para a tela e para
 * o navegador do usuário.
 */
class MontadorDeCardDePesquisaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String AGORA = "14/10/2026 09:30";

    /** Uma entrada válida de ranking, para cada teste estragar só o campo que lhe interessa. */
    private static ObjectNode valida() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("categoria", "esporte");
        entrada.put("tema", "Brasileirão 2026");
        entrada.put("titulo", "Flamengo lidera com 12 pontos de vantagem");
        entrada.put("referencia", "após a 28ª rodada");
        entrada.put("layout", "ranking");
        entrada.put("resumo", "O Flamengo lidera o Brasileirão com 12 pontos de vantagem.");
        ArrayNode itens = entrada.putArray("itens");
        item(itens, 1, "Flamengo", 64d, "pts");
        item(itens, 2, "Palmeiras", 52d, "pts");
        return entrada;
    }

    private static void item(ArrayNode itens, int posicao, String nome, Double valor, String unidade) {
        ObjectNode item = itens.addObject();
        item.put("posicao", posicao);
        item.put("nome", nome);
        if (valor != null) {
            item.put("valor", valor);
        }
        item.put("unidade", unidade);
    }

    @Test
    @DisplayName("uma entrada completa vira card de pesquisa, com o titulo nomeando a janela")
    void entradaValida() {
        CardTela card = MontadorDeCardDePesquisa.montar(valida(), "telegram", AGORA);

        assertEquals(CardTela.PESQUISA, card.tipo());
        assertEquals("Flamengo lidera com 12 pontos de vantagem", card.titulo(),
                "o titulo do card é a conclusão, que é o que a janela mostra no alto");
        assertEquals("telegram", card.origem());
        assertTrue(card.id().startsWith("c"), card.id());

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class, card.dados());
        assertEquals("Brasileirão 2026", dados.tema(), "o tema é o assunto e fica nos dados");
        assertEquals(AGORA, dados.consultadoEm());
        assertEquals(2, dados.itens().size());
        var primeiro = assertInstanceOf(DadosDoCard.ItemDoCard.Posicao.class, dados.itens().getFirst());
        assertEquals(1, primeiro.posicao());
        assertEquals("Flamengo", primeiro.nome());
        assertEquals(64d, primeiro.valor());
    }

    @Test
    @DisplayName("o consultadoEm vem do relógio do servidor e ignora o que o modelo mandar")
    void consultadoEmNaoVemDoModelo() {
        ObjectNode entrada = valida();
        entrada.put("consultadoEm", "01/01/1999 00:00");

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA).dados());

        assertEquals(AGORA, dados.consultadoEm(),
                "data que o modelo inventa é justamente a que a tela mostra como se fosse apurada");
    }

    @Test
    @DisplayName("URL de fonte só pode ser http ou https")
    void urlDeFonteInvalida() {
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.urlSegura("ftp://exemplo.com/dados.csv"));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.urlSegura("javascript:alert(1)"),
                "essa URL vira link clicável na HUD: javascript aqui é script rodando na tela");
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.urlSegura("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.urlSegura("https://usuario:senha@exemplo.com/"));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.urlSegura("https:///sem-host"));

        assertEquals("https://g1.globo.com/esporte",
                MontadorDeCardDePesquisa.urlSegura("https://g1.globo.com/esporte"));
        assertEquals("", MontadorDeCardDePesquisa.urlSegura(""), "fonte sem link acontece");
    }

    @Test
    @DisplayName("a fonte com URL ruim derruba a montagem inteira do card")
    void fonteRuimNoCard() {
        ObjectNode entrada = valida();
        ObjectNode fonte = entrada.putArray("fontes").addObject();
        fonte.put("nome", "Exemplo");
        fonte.put("url", "javascript:alert(1)");

        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA));
    }

    @Test
    @DisplayName("percentual fora de 0 a 100 é recusado")
    void percentualForaDaFaixa() {
        for (double fora : new double[] { 140d, -5d, 100.5d }) {
            ObjectNode entrada = valida();
            ArrayNode itens = entrada.putArray("itens");
            item(itens, 1, "Candidato A", fora, "%");

            IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                    () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA));
            assertTrue(erro.getMessage().contains("0-100"), erro.getMessage());
        }
    }

    @Test
    @DisplayName("percentual dentro da faixa passa, inclusive escrito com vírgula")
    void percentualValido() {
        ObjectNode entrada = valida();
        ArrayNode itens = entrada.putArray("itens");
        item(itens, 1, "Candidato A", 42.5d, "%");
        ObjectNode comVirgula = itens.addObject();
        comVirgula.put("posicao", 2);
        comVirgula.put("nome", "Candidato B");
        comVirgula.put("valor", "31,8");
        comVirgula.put("unidade", "%");

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA).dados());

        assertEquals(42.5d,
                ((DadosDoCard.ItemDoCard.Posicao) dados.itens().get(0)).valor());
        assertEquals(31.8d,
                ((DadosDoCard.ItemDoCard.Posicao) dados.itens().get(1)).valor(),
                "o modelo escreve número em português; aceitar a vírgula custa uma linha");
    }

    @Test
    @DisplayName("mais de 8 itens é recusado, e a mensagem diz quantos vieram")
    void itensDemais() {
        ObjectNode entrada = valida();
        ArrayNode itens = entrada.putArray("itens");
        for (int i = 1; i <= 9; i++) {
            item(itens, i, "Time " + i, (double) (60 - i), "pts");
        }

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA));
        assertTrue(erro.getMessage().contains("9"), erro.getMessage());
    }

    @Test
    @DisplayName("lista de itens vazia é recusada")
    void semItens() {
        ObjectNode entrada = valida();
        entrada.putArray("itens");

        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA));
    }

    @Test
    @DisplayName("texto acima do teto é recusado, campo por campo")
    void textoLongoDemais() {
        ObjectNode comTitulo = valida();
        comTitulo.put("titulo", "x".repeat(MontadorDeCardDePesquisa.MAX_TITULO + 1));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(comTitulo, "hud", AGORA));

        ObjectNode comResumo = valida();
        comResumo.put("resumo", "x".repeat(601));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(comResumo, "hud", AGORA));

        ObjectNode comReferencia = valida();
        comReferencia.put("referencia", "x".repeat(41));
        assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(comReferencia, "hud", AGORA));
    }

    @Test
    @DisplayName("campo obrigatório faltando é recusado com o nome do campo")
    void camposObrigatorios() {
        for (String campo : new String[] { "categoria", "tema", "titulo", "layout", "resumo" }) {
            ObjectNode entrada = valida();
            entrada.remove(campo);

            IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                    () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA), campo);
            assertTrue(erro.getMessage().contains(campo), erro.getMessage());
        }
    }

    @Test
    @DisplayName("layout fora do enum é recusado")
    void layoutDesconhecido() {
        ObjectNode entrada = valida();
        entrada.put("layout", "carrossel");

        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA));
        assertTrue(erro.getMessage().contains("carrossel"), erro.getMessage());
    }

    @Test
    @DisplayName("cada layout produz a sua forma de item")
    void cadaLayoutTemSuaForma() {
        assertInstanceOf(DadosDoCard.ItemDoCard.Campo.class,
                primeiroItem("ficha", campo("rotulo", "Altura", "valor", "1,80 m")));
        assertInstanceOf(DadosDoCard.ItemDoCard.Marco.class,
                primeiroItem("linha_do_tempo", campo("data", "1988", "texto", "Constituição")));
        assertInstanceOf(DadosDoCard.ItemDoCard.Numero.class,
                primeiroItem("destaque", campo("valor", "R$ 5,12", "legenda", "dólar hoje")));
        assertInstanceOf(DadosDoCard.ItemDoCard.Linha.class,
                primeiroItem("lista", campo("texto", "Primeiro ponto", null, null)));
    }

    @Test
    @DisplayName("item sem o campo que o layout exige é recusado, dizendo qual item")
    void itemIncompleto() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> primeiroItem("ficha", campo("rotulo", "Altura", null, null)));
        assertTrue(erro.getMessage().contains("item 1"), erro.getMessage());
    }

    // ---------------------------------------------------------------- gráfico

    /** Uma entrada válida de gráfico de linha, para cada teste estragar só o que lhe interessa. */
    private static ObjectNode validaGrafico() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("categoria", "clima");
        entrada.put("tema", "Previsão para São Paulo");
        entrada.put("titulo", "A temperatura cai 8 °C até quinta");
        entrada.put("referencia", "23/10/2026");
        entrada.put("layout", "grafico");
        entrada.put("resumo", "A máxima cai de 31 para 23 graus até quinta.");
        ObjectNode fonte = entrada.putArray("fontes").addObject();
        fonte.put("nome", "Inmet");
        fonte.put("url", "https://portal.inmet.gov.br/");
        entrada.set("grafico", grafico("linha", new String[] { "seg", "ter", "qua", "qui" },
                new double[] { 31, 29, 26, 23 }));
        return entrada;
    }

    private static ObjectNode grafico(String tipo, String[] rotulos, double[] valores) {
        ObjectNode grafico = JSON.createObjectNode();
        grafico.put("tipo", tipo);
        grafico.put("unidade", "°C");
        ArrayNode eixoX = grafico.putArray("eixoX");
        for (String rotulo : rotulos) {
            eixoX.add(rotulo);
        }
        serie(grafico.putArray("series"), "Máxima", valores);
        return grafico;
    }

    private static void serie(ArrayNode series, String nome, double[] valores) {
        ObjectNode serie = series.addObject();
        serie.put("nome", nome);
        ArrayNode numeros = serie.putArray("valores");
        for (double valor : valores) {
            numeros.add(valor);
        }
    }

    /** O gráfico de uma entrada que tem de passar. */
    private static DadosDoCard.Grafico montarGrafico(ObjectNode entrada) {
        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA).dados());
        return dados.grafico();
    }

    /** A mensagem do erro que a entrada estragada produz. */
    private static String erroDe(ObjectNode entrada) {
        return assertThrows(IllegalArgumentException.class,
                () -> MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA)).getMessage();
    }

    @Test
    @DisplayName("o layout grafico vira um Grafico, sem itens e com a origem marcada como pesquisa")
    void graficoValido() {
        ObjectNode entrada = validaGrafico();

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA).dados());

        assertTrue(dados.itens().isEmpty(), "card de gráfico não tem itens");
        DadosDoCard.Grafico g = dados.grafico();
        assertEquals("linha", g.tipo());
        assertEquals(List.of("seg", "ter", "qua", "qui"), g.eixoX());
        assertEquals(1, g.series().size());
        assertEquals("Máxima", g.series().getFirst().nome());
        assertEquals(List.of(31d, 29d, 26d, 23d), g.series().getFirst().valores());
        assertEquals("°C", g.unidade());
        assertEquals("pesquisa", g.origem());
        assertNull(g.destaque(), "destaque é opcional");
    }

    @Test
    @DisplayName("nos outros layouts o grafico fica nulo")
    void semGraficoNosOutrosLayouts() {
        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                MontadorDeCardDePesquisa.montar(valida(), "hud", AGORA).dados());

        assertNull(dados.grafico());
    }

    @Test
    @DisplayName("gráfico de barras aceita uma categoria só e até doze")
    void barras() {
        assertEquals("barras", montarGrafico(comGrafico("barras", 1)).tipo());
        assertEquals(12, montarGrafico(comGrafico("barras", 12)).eixoX().size());

        assertTrue(erroDe(comGrafico("barras", 13)).contains("13"), "treze barras viram um pente");
    }

    @Test
    @DisplayName("gráfico de linha precisa de 2 a 24 pontos")
    void pontosDaLinha() {
        assertEquals(2, montarGrafico(comGrafico("linha", 2)).eixoX().size());
        assertEquals(24, montarGrafico(comGrafico("linha", 24)).eixoX().size());

        assertTrue(erroDe(comGrafico("linha", 1)).contains("2 a 24"), "um ponto não é uma linha");
        assertTrue(erroDe(comGrafico("linha", 25)).contains("25"));
    }

    /** Uma entrada de gráfico com N rótulos e uma série do mesmo tamanho. */
    private static ObjectNode comGrafico(String tipo, int pontos) {
        String[] rotulos = new String[pontos];
        double[] valores = new double[pontos];
        for (int i = 0; i < pontos; i++) {
            rotulos[i] = "p" + (i + 1);
            valores[i] = i + 1;
        }
        ObjectNode entrada = validaGrafico();
        entrada.set("grafico", grafico(tipo, rotulos, valores));
        return entrada;
    }

    @Test
    @DisplayName("de 1 a 3 séries; a quarta é recusada")
    void quantidadeDeSeries() {
        ObjectNode tres = validaGrafico();
        ArrayNode series = (ArrayNode) tres.path("grafico").path("series");
        serie(series, "Mínima", new double[] { 20, 19, 18, 17 });
        serie(series, "Sensação", new double[] { 33, 31, 28, 25 });
        assertEquals(3, montarGrafico(tres).series().size());

        ObjectNode quatro = validaGrafico();
        ArrayNode demais = (ArrayNode) quatro.path("grafico").path("series");
        serie(demais, "Mínima", new double[] { 20, 19, 18, 17 });
        serie(demais, "Sensação", new double[] { 33, 31, 28, 25 });
        serie(demais, "Chuva", new double[] { 1, 2, 3, 4 });
        assertTrue(erroDe(quatro).contains("4"), erroDe(quatro));

        ObjectNode nenhuma = validaGrafico();
        ((ObjectNode) nenhuma.path("grafico")).putArray("series");
        assertTrue(erroDe(nenhuma).contains("series"), erroDe(nenhuma));
    }

    @Test
    @DisplayName("série de tamanho diferente do eixoX é recusada, dizendo os dois números")
    void serieDeOutroTamanho() {
        ObjectNode entrada = validaGrafico();
        ObjectNode g = (ObjectNode) entrada.path("grafico");
        g.putArray("series");
        serie((ArrayNode) g.path("series"), "Máxima", new double[] { 31, 29, 26 });

        String erro = erroDe(entrada);
        assertTrue(erro.contains("3 valores") && erro.contains("4 pontos"), erro);
    }

    @Test
    @DisplayName("NaN, infinito e texto que não é número são recusados")
    void valorQueNaoEhNumero() {
        ObjectNode comNaN = validaGrafico();
        valorCru(comNaN, 1, JSON.getNodeFactory().numberNode(Double.NaN));
        assertTrue(erroDe(comNaN).contains("valor 2"), erroDe(comNaN));

        ObjectNode comTextoNaN = validaGrafico();
        valorCru(comTextoNaN, 1, JSON.getNodeFactory().stringNode("NaN"));
        assertTrue(erroDe(comTextoNaN).contains("número finito"),
                "'NaN' em texto passa pelo Double.valueOf e viraria um buraco na linha");

        ObjectNode comInfinito = validaGrafico();
        valorCru(comInfinito, 0, JSON.getNodeFactory().numberNode(Double.POSITIVE_INFINITY));
        assertTrue(erroDe(comInfinito).contains("finito"), erroDe(comInfinito));

        ObjectNode comTexto = validaGrafico();
        valorCru(comTexto, 2, JSON.getNodeFactory().stringNode("quente"));
        assertTrue(erroDe(comTexto).contains("valor 3"), erroDe(comTexto));
    }

    @Test
    @DisplayName("número escrito com vírgula é aceito, como no ranking")
    void valorComVirgula() {
        ObjectNode entrada = validaGrafico();
        valorCru(entrada, 0, JSON.getNodeFactory().stringNode("31,5"));

        assertEquals(31.5d, montarGrafico(entrada).series().getFirst().valores().getFirst());
    }

    private static void valorCru(ObjectNode entrada, int indice, JsonNode no) {
        ArrayNode valores = (ArrayNode) entrada.path("grafico").path("series").get(0).path("valores");
        valores.set(indice, no);
    }

    @Test
    @DisplayName("tipo de gráfico fora dos dois conhecidos é recusado")
    void tipoDesconhecido() {
        ObjectNode entrada = validaGrafico();
        ((ObjectNode) entrada.path("grafico")).put("tipo", "pizza");

        assertTrue(erroDe(entrada).contains("pizza"), erroDe(entrada));
    }

    @Test
    @DisplayName("layout grafico sem o objeto grafico é recusado")
    void graficoFaltando() {
        ObjectNode entrada = validaGrafico();
        entrada.remove("grafico");

        assertTrue(erroDe(entrada).contains("grafico"), erroDe(entrada));
    }

    @Test
    @DisplayName("rótulo, nome de série e unidade têm teto de tamanho")
    void tamanhosDoGrafico() {
        ObjectNode comRotulo = validaGrafico();
        ((ArrayNode) comRotulo.path("grafico").path("eixoX")).set(0,
                JSON.getNodeFactory().stringNode("x".repeat(25)));
        assertTrue(erroDe(comRotulo).contains("eixoX 1"), erroDe(comRotulo));

        ObjectNode comNome = validaGrafico();
        ((ObjectNode) comNome.path("grafico").path("series").get(0)).put("nome", "x".repeat(41));
        assertTrue(erroDe(comNome).contains("série 1"), erroDe(comNome));

        ObjectNode comUnidade = validaGrafico();
        ((ObjectNode) comUnidade.path("grafico")).put("unidade", "x".repeat(11));
        assertTrue(erroDe(comUnidade).contains("unidade"), erroDe(comUnidade));

        ObjectNode vazio = validaGrafico();
        ((ArrayNode) vazio.path("grafico").path("eixoX")).set(1, JSON.getNodeFactory().stringNode(" "));
        assertTrue(erroDe(vazio).contains("eixoX 2"), erroDe(vazio));
    }

    @Test
    @DisplayName("o modelo não pode marcar um gráfico como vindo do servidor")
    void origemDoServidorRecusada() {
        ObjectNode entrada = validaGrafico();
        ((ObjectNode) entrada.path("grafico")).put("origem", "servidor");

        String erro = erroDe(entrada);
        assertTrue(erro.contains("servidor"), erro);

        ObjectNode inventada = validaGrafico();
        ((ObjectNode) inventada.path("grafico")).put("origem", "memória");
        assertTrue(erroDe(inventada).contains("pesquisa"), erroDe(inventada));
    }

    @Test
    @DisplayName("gráfico de pesquisa sem fonte ou sem referência é recusado")
    void pesquisaPrecisaDeProcedencia() {
        ObjectNode semFonte = validaGrafico();
        semFonte.remove("fontes");
        assertTrue(erroDe(semFonte).contains("fonte"), erroDe(semFonte));

        ObjectNode semReferencia = validaGrafico();
        semReferencia.remove("referencia");
        assertTrue(erroDe(semReferencia).contains("referencia"), erroDe(semReferencia));
    }

    @Test
    @DisplayName("o destaque aponta série e ponto que existem")
    void destaque() {
        ObjectNode valido = validaGrafico();
        ObjectNode ponto = ((ObjectNode) valido.path("grafico")).putObject("destaque");
        ponto.put("serie", 0);
        ponto.put("indice", 3);
        assertEquals(new DadosDoCard.Grafico.Destaque(0, 3), montarGrafico(valido).destaque());

        ObjectNode serieDemais = validaGrafico();
        ((ObjectNode) serieDemais.path("grafico")).putObject("destaque").put("serie", 2);
        assertTrue(erroDe(serieDemais).contains("série 2"), erroDe(serieDemais));

        ObjectNode pontoDemais = validaGrafico();
        ((ObjectNode) pontoDemais.path("grafico")).putObject("destaque").put("indice", 9);
        assertTrue(erroDe(pontoDemais).contains("ponto 9"), erroDe(pontoDemais));
    }

    @Test
    @DisplayName("o JSON que vai para a HUD leva o gráfico no caminho dados.grafico")
    void jsonParaAHud() {
        ObjectNode entrada = validaGrafico();
        ObjectNode ponto = ((ObjectNode) entrada.path("grafico")).putObject("destaque");
        ponto.put("serie", 0);
        ponto.put("indice", 3);

        CardTela card = MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA);
        JsonNode json = JSON.valueToTree(card);

        assertEquals("pesquisa", json.path("tipo").asString());
        assertEquals("grafico", json.path("dados").path("layout").asString());
        assertTrue(json.path("dados").path("itens").isEmpty(), "sem itens no card de gráfico");
        JsonNode grafico = json.path("dados").path("grafico");
        assertEquals("linha", grafico.path("tipo").asString());
        assertEquals("qui", grafico.path("eixoX").get(3).asString());
        assertEquals(29d, grafico.path("series").get(0).path("valores").get(1).doubleValue());
        assertEquals("Máxima", grafico.path("series").get(0).path("nome").asString());
        assertEquals(3, grafico.path("destaque").path("indice").asInt());
        assertEquals("pesquisa", grafico.path("origem").asString());
    }

    private static ObjectNode campo(String a, String valorA, String b, String valorB) {
        ObjectNode item = JSON.createObjectNode();
        item.put(a, valorA);
        if (b != null) {
            item.put(b, valorB);
        }
        return item;
    }

    private static DadosDoCard.ItemDoCard primeiroItem(String layout, ObjectNode item) {
        ObjectNode entrada = valida();
        entrada.put("layout", layout);
        entrada.putArray("itens").add(item);
        var dados = (DadosDoCard.Pesquisa) MontadorDeCardDePesquisa.montar(entrada, "hud", AGORA).dados();
        return dados.itens().getFirst();
    }
}
