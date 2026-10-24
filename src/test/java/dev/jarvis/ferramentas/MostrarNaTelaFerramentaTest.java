package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Testa a ferramenta que abre o card de pesquisa: a guarda de tela fechada e a origem do card,
 * que é o que distingue o pedido feito pelo celular do feito na própria HUD.
 */
class MostrarNaTelaFerramentaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JarvisProperties PROPS = new JarvisProperties("Anderson",
            "America/Sao_Paulo", "", null, null, null, null, null, null, null, null, null, null);

    private static ObjectNode entrada() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("categoria", "economia");
        entrada.put("tema", "Dólar");
        entrada.put("titulo", "Dólar fecha a R$ 5,12, menor valor do ano");
        entrada.put("layout", "destaque");
        entrada.put("resumo", "O dólar fechou a cinco reais e doze centavos.");
        ObjectNode item = entrada.putArray("itens").addObject();
        item.put("valor", "R$ 5,12");
        item.put("legenda", "fechamento de hoje");
        return entrada;
    }

    private static MostrarNaTelaFerramenta com(PresencaDaHud hud) {
        return new MostrarNaTelaFerramenta(hud, JSON, PROPS);
    }

    @Test
    @DisplayName("sem HUD conectada não abre card nenhum e avisa o modelo")
    void semTelaAberta() throws Exception {
        ResultadoFerramenta resultado =
                com(PresencaDaHud.semTela()).executarDetalhado(entrada(), new OuvinteProgresso() { });

        assertEquals(FerramentaDeTela.SEM_TELA, resultado.texto());
        assertTrue(resultado.cards().isEmpty(),
                "abrir card para ninguém deixaria o modelo prometendo o que não aconteceu");
    }

    @Test
    @DisplayName("a origem do card é o canal do pedido: card pedido pelo Telegram abre na tela")
    void origemVemDoCanal() throws Exception {
        OuvinteProgresso doTelegram = new OuvinteProgresso() {
            @Override public String canal() { return "telegram"; }
        };

        ResultadoFerramenta resultado = com(() -> 1).executarDetalhado(entrada(), doTelegram);

        assertEquals(1, resultado.cards().size());
        CardTela card = resultado.cards().getFirst();
        assertEquals("telegram", card.origem());
        assertEquals(CardTela.PESQUISA, card.tipo());
        assertEquals("Dólar fecha a R$ 5,12, menor valor do ano", card.titulo());
        assertTrue(resultado.texto().contains(card.id()), resultado.texto());
    }

    @Test
    @DisplayName("sem canal informado, a origem fica 'desconhecido' em vez de vazia")
    void origemDesconhecida() throws Exception {
        ResultadoFerramenta resultado =
                com(() -> 1).executarDetalhado(entrada(), new OuvinteProgresso() { });

        assertEquals("desconhecido", resultado.cards().getFirst().origem());
    }

    @Test
    @DisplayName("o consultadoEm é preenchido pelo relógio do servidor")
    void consultadoEmDoServidor() throws Exception {
        ResultadoFerramenta resultado =
                com(() -> 1).executarDetalhado(entrada(), new OuvinteProgresso() { });

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                resultado.cards().getFirst().dados());
        assertTrue(dados.consultadoEm().matches("\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}"),
                dados.consultadoEm());
    }

    @Test
    @DisplayName("entrada inválida estoura, e o registro de ferramentas devolve isso como erro")
    void entradaInvalida() {
        ObjectNode semResumo = entrada();
        semResumo.remove("resumo");

        assertThrows(IllegalArgumentException.class,
                () -> com(() -> 1).executarDetalhado(semResumo, new OuvinteProgresso() { }));
    }

    @Test
    @DisplayName("o schema anuncia os layouts e o teto de itens")
    void schema() {
        ObjectNode esquema = com(() -> 1).esquemaEntrada();

        assertEquals(6, esquema.path("properties").path("layout").path("enum").size());
        assertEquals(8, esquema.path("properties").path("itens").path("maxItems").asInt());
        assertTrue(esquema.path("required").toString().contains("resumo"));
        assertFalse(esquema.path("required").toString().contains("itens"),
                "o layout grafico não tem itens; quem cobra um ou outro é o montador");
    }

    @Test
    @DisplayName("o schema do grafico anuncia os quatro tipos e não expõe a origem")
    void schemaDoGrafico() {
        ObjectNode grafico = (ObjectNode) com(() -> 1).esquemaEntrada()
                .path("properties").path("grafico");

        assertEquals(4, grafico.path("properties").path("tipo").path("enum").size());
        // Quatro é o teto do tipo mais generoso; o teto de cada tipo é cobrado pelo montador,
        // que sabe qual veio — anunciar 3 aqui barraria a quarta parte da barra empilhada.
        assertEquals(4, grafico.path("properties").path("series").path("maxItems").asInt());
        assertTrue(grafico.path("required").toString().contains("series"));
        assertTrue(grafico.path("properties").path("origem").isMissingNode(),
                "marcar um número como vindo do próprio Jarvis é trabalho do Java");
    }

    @Test
    @DisplayName("card de gráfico sai pela ferramenta sem itens")
    void cardDeGrafico() throws Exception {
        ObjectNode entrada = entrada();
        entrada.remove("itens");
        entrada.put("layout", "grafico");
        entrada.put("referencia", "23/10/2026");
        ObjectNode fonte = entrada.putArray("fontes").addObject();
        fonte.put("nome", "Banco Central");
        fonte.put("url", "https://www.bcb.gov.br/");
        ObjectNode grafico = entrada.putObject("grafico");
        grafico.put("tipo", "linha");
        grafico.putArray("eixoX").add("seg").add("ter").add("qua");
        ObjectNode serie = grafico.putArray("series").addObject();
        serie.put("nome", "Dólar");
        serie.putArray("valores").add(5.3).add(5.2).add(5.12);

        ResultadoFerramenta resultado =
                com(() -> 1).executarDetalhado(entrada, new OuvinteProgresso() { });

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                resultado.cards().getFirst().dados());
        assertTrue(dados.itens().isEmpty());
        assertEquals("linha", dados.grafico().tipo());
        assertEquals("pesquisa", dados.grafico().origem());
    }

    @Test
    @DisplayName("card de rosca sai pela ferramenta: fatias no eixoX e uma série só")
    void cardDeRosca() throws Exception {
        ObjectNode entrada = entrada();
        entrada.remove("itens");
        entrada.put("layout", "grafico");
        entrada.put("referencia", "outubro de 2026");
        ObjectNode fonte = entrada.putArray("fontes").addObject();
        fonte.put("nome", "StatCounter");
        fonte.put("url", "https://gs.statcounter.com/");
        ObjectNode grafico = entrada.putObject("grafico");
        grafico.put("tipo", "rosca");
        grafico.put("unidade", "%");
        grafico.putArray("eixoX").add("Chrome").add("Safari").add("Edge");
        ObjectNode serie = grafico.putArray("series").addObject();
        serie.put("nome", "fatia do mercado");
        serie.putArray("valores").add(66.4).add(18.1).add(5.2);

        ResultadoFerramenta resultado =
                com(() -> 1).executarDetalhado(entrada, new OuvinteProgresso() { });

        var dados = assertInstanceOf(DadosDoCard.Pesquisa.class,
                resultado.cards().getFirst().dados());
        assertEquals("rosca", dados.grafico().tipo());
        assertEquals(3, dados.grafico().eixoX().size());
        assertEquals(1, dados.grafico().series().size());
    }
}
