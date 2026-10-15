package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.briefing.DadosSecao;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.EstadoDaTela;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.TelaEstadoService;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Testa o controlar_tela. O ponto que mais importa: alvo que não está na tela não vira ordem
 * nenhuma — o modelo recebe de volta a lista do que está aberto, e não um comando no vazio.
 */
class ControlarTelaFerramentaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final OuvinteProgresso DA_HUD = new OuvinteProgresso() {
        @Override public String canal() { return "hud"; }
    };

    private final BriefingService briefing = mock(BriefingService.class);
    private final TelaEstadoService tela = new TelaEstadoService();

    private ControlarTelaFerramenta comTela() {
        tela.guardar(new EstadoDaTela(
                List.of(new EstadoDaTela.Janela("c7k2", CardTela.VIDEO, "Lo-fi para estudar",
                        "normal", false, false, true)),
                false, false, Instant.now().toString()));
        return new ControlarTelaFerramenta(() -> 1, JSON, tela, briefing);
    }

    private static ObjectNode pedido(String acao, String alvo, String valor) {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("acao", acao);
        if (alvo != null) {
            entrada.put("alvo", alvo);
        }
        if (valor != null) {
            entrada.put("valor", valor);
        }
        return entrada;
    }

    @Test
    @DisplayName("alvo que não está na tela não gera comando, e a resposta lista o que está aberto")
    void alvoInexistente() throws Exception {
        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("fechar", "c999", null), DA_HUD);

        assertTrue(resultado.comandos().isEmpty(), "ordem para janela que não existe não pode sair");
        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("c7k2"),
                "a resposta tem de dizer o que está aberto: " + resultado.texto());
    }

    @Test
    @DisplayName("alvo existente gera exatamente um comando")
    void alvoExistente() throws Exception {
        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("fechar", "c7k2", null), DA_HUD);

        assertEquals(1, resultado.comandos().size());
        assertEquals("fechar", resultado.comandos().getFirst().acao());
        assertEquals("c7k2", resultado.comandos().getFirst().alvo());
        assertEquals("", resultado.comandos().getFirst().valor());
    }

    @Test
    @DisplayName("ação que precisa de alvo e veio sem ele também não gera comando")
    void alvoFaltando() throws Exception {
        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("minimizar", null, null), DA_HUD);

        assertTrue(resultado.comandos().isEmpty());
        assertTrue(resultado.texto().contains("precisa do id"), resultado.texto());
    }

    @Test
    @DisplayName("ação que vale para a tela inteira não exige alvo")
    void acaoSemAlvo() throws Exception {
        ResultadoFerramenta resultado = comTela().executarDetalhado(pedido("limpar", null, null), DA_HUD);

        assertEquals(1, resultado.comandos().size());
        assertEquals("limpar", resultado.comandos().getFirst().acao());
        assertTrue(resultado.texto().contains("Tela limpa"), resultado.texto());
    }

    @Test
    @DisplayName("ação fora do enum é recusada e a resposta lista as válidas")
    void acaoDesconhecida() throws Exception {
        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("explodir", "c7k2", null), DA_HUD);

        assertTrue(resultado.comandos().isEmpty());
        assertTrue(resultado.texto().contains("fechar_tudo"), resultado.texto());
    }

    @Test
    @DisplayName("sem HUD conectada nada sai, e o modelo é avisado")
    void semTelaAberta() throws Exception {
        var ferramenta = new ControlarTelaFerramenta(PresencaDaHud.semTela(), JSON, tela, briefing);

        ResultadoFerramenta resultado =
                ferramenta.executarDetalhado(pedido("limpar", null, null), DA_HUD);

        assertEquals(FerramentaDeTela.SEM_TELA, resultado.texto());
        assertTrue(resultado.comandos().isEmpty());
        assertTrue(resultado.cards().isEmpty());
    }

    @Test
    @DisplayName("abrir_noticia acha a manchete do briefing mesmo escrita sem acento")
    void abrirNoticiaSemAcento() throws Exception {
        when(briefing.ultimo()).thenReturn(Optional.of(comNoticias()));

        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("abrir_noticia", null, "eleicao 2026"), DA_HUD);

        assertEquals(1, resultado.cards().size());
        CardTela card = resultado.cards().getFirst();
        assertEquals(CardTela.NOTICIA, card.tipo());
        assertEquals("hud", card.origem());
        var dados = assertInstanceOf(DadosDoCard.Noticia.class, card.dados());
        assertEquals("Eleição 2026 já movimenta os partidos", dados.titulo());
        assertEquals("https://g1.globo.com/politica/eleicao", dados.link());
        assertTrue(resultado.comandos().isEmpty(), "a notícia vai como card, não como ordem");
    }

    @Test
    @DisplayName("abrir_noticia também casa pelo tema quando o título não bate")
    void abrirNoticiaPeloTema() throws Exception {
        when(briefing.ultimo()).thenReturn(Optional.of(comNoticias()));

        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("abrir_noticia", null, "tecnologia"), DA_HUD);

        assertEquals(1, resultado.cards().size());
        var dados = (DadosDoCard.Noticia) resultado.cards().getFirst().dados();
        assertEquals("tecnologia", dados.tema());
    }

    @Test
    @DisplayName("notícia que não está no briefing não é inventada: a resposta oferece busca na web")
    void abrirNoticiaNaoEncontrada() throws Exception {
        when(briefing.ultimo()).thenReturn(Optional.of(comNoticias()));

        ResultadoFerramenta resultado = comTela()
                .executarDetalhado(pedido("abrir_noticia", null, "pouso na lua"), DA_HUD);

        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("web"), resultado.texto());
    }

    @Test
    @DisplayName("sem briefing nenhum, abrir_noticia diz que não achou")
    void abrirNoticiaSemBriefing() throws Exception {
        when(briefing.ultimo()).thenReturn(Optional.empty());

        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("abrir_noticia", null, "eleicao"), DA_HUD);

        assertTrue(resultado.cards().isEmpty());
        assertFalse(resultado.texto().isBlank());
    }

    @Test
    @DisplayName("abrir_noticia sem o texto procurado pede o texto")
    void abrirNoticiaSemValor() throws Exception {
        ResultadoFerramenta resultado =
                comTela().executarDetalhado(pedido("abrir_noticia", null, null), DA_HUD);

        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("valor"), resultado.texto());
    }

    @Test
    @DisplayName("o schema anuncia as 20 ações")
    void schemaComAsAcoes() {
        var enumDeAcoes = comTela().esquemaEntrada()
                .path("properties").path("acao").path("enum");

        assertEquals(dev.jarvis.tela.ComandoTela.ACOES.size(), enumDeAcoes.size());
    }

    private static BriefingService.Briefing comNoticias() {
        var noticias = new DadosSecao.Noticias(List.of(
                new DadosSecao.Noticias.Item("Eleição 2026 já movimenta os partidos", "politica",
                        "G1", "https://g1.globo.com/politica/eleicao", ""),
                new DadosSecao.Noticias.Item("Nova geração de chips chega ao Brasil", "tecnologia",
                        "Ars Technica", "https://arstechnica.com/chips", "https://img/chip.jpg")));
        return new BriefingService.Briefing(
                ZonedDateTime.of(2026, 10, 14, 8, 0, 0, 0, ZoneId.of("America/Sao_Paulo")),
                "Anderson",
                List.of(new BriefingService.Secao("clima", "19 graus agora."),
                        new BriefingService.Secao("notícias", "duas manchetes", noticias)));
    }
}
