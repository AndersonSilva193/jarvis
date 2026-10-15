package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
