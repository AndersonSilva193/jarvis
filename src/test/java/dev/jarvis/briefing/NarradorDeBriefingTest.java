package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.briefing.BriefingService.Secao;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.SistemaDoJarvis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** A narração do briefing, com o ClaudeClient dublado: sem rede e sem Spring. */
class NarradorDeBriefingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<Secao> SECOES = List.of(
            new Secao("clima", "Curitiba: 18 °C agora (nublado)"),
            new Secao("sistemas", "agendabarber-api: HTTP 200 em 180 ms (OK)"));

    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
    private final NarradorDeBriefing narrador = new NarradorDeBriefing(claude, sistema, JSON);

    /** Sem isto o dublê devolveria null e a chamada iria sem system nenhum. */
    @BeforeEach
    void prepararSistema() {
        ArrayNode blocos = JSON.createArrayNode();
        blocos.addObject().put("type", "text").put("text", "personalidade");
        when(sistema.blocos(any(Canal.class))).thenReturn(blocos);
    }

    private static JsonNode resposta(String texto) {
        ObjectNode resposta = JSON.createObjectNode();
        resposta.put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", texto);
        return resposta;
    }

    private void respondeComoModelo(String texto) throws Exception {
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                .thenReturn(resposta(texto));
    }

    @Nested
    @DisplayName("chamada")
    class Chamada {

        @Test
        @DisplayName("devolve a abertura, uma fala por seção e as observações do fim")
        void narraCadaSecao() throws Exception {
            respondeComoModelo("""
                    [0] Bom dia, senhor. Seu briefing está pronto.
                    [1] Dezoito graus e nublado em Curitiba.
                    [2] A API do AgendaBarber responde normalmente.
                    [3] Dia tranquilo, senhor. Nada que peça a sua atenção.""");

            List<String> falas = narrador.narrar("Anderson", SECOES);

            assertEquals(4, falas.size(), "abertura + uma por seção + observações");
            assertEquals("Bom dia, senhor. Seu briefing está pronto.", falas.get(0));
            assertEquals("A API do AgendaBarber responde normalmente.", falas.get(2));
            assertEquals("Dia tranquilo, senhor. Nada que peça a sua atenção.", falas.get(3));
        }

        @Test
        @DisplayName("pede o system do canal briefing, que é o das regras de fala")
        void usaOCanalBriefing() throws Exception {
            respondeComoModelo("[0] a\n[1] b\n[2] c\n[3] d");

            narrador.narrar("Anderson", SECOES);

            verify(sistema).blocos(Canal.BRIEFING);
        }

        @Test
        @DisplayName("não manda ferramenta nenhuma: o modelo não pode chamar o briefing de dentro do briefing")
        void semFerramentas() throws Exception {
            respondeComoModelo("[0] a\n[1] b\n[2] c\n[3] d");

            narrador.narrar("Anderson", SECOES);

            ArgumentCaptor<ArrayNode> ferramentas = ArgumentCaptor.forClass(ArrayNode.class);
            verify(claude).criarMensagem(any(ArrayNode.class), any(ArrayNode.class), ferramentas.capture());
            assertTrue(ferramentas.getValue().isEmpty(), "a narração não pode levar ferramentas");
        }

        @Test
        @DisplayName("manda o conteúdo das seções e a ordem de não alterar dado")
        void mandaOsDadosEAsRegras() throws Exception {
            respondeComoModelo("[0] a\n[1] b\n[2] c\n[3] d");

            narrador.narrar("Anderson", SECOES);

            ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
            verify(claude).criarMensagem(any(ArrayNode.class), mensagens.capture(), any(ArrayNode.class));
            String conteudo = mensagens.getValue().get(0).path("content").stringValue();

            assertTrue(conteudo.contains("Curitiba: 18 °C agora (nublado)"), conteudo);
            assertTrue(conteudo.contains("não altere nenhum número"), conteudo);
        }

        @Test
        @DisplayName("falha na API devolve vazio, para quem pediu ficar com o texto montado em Java")
        void falhaNaoEstoura() throws Exception {
            when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class)))
                    .thenThrow(new IOException("API do Claude respondeu HTTP 529"));

            assertTrue(narrador.narrar("Anderson", SECOES).isEmpty());
        }

        @Test
        @DisplayName("briefing sem seção nenhuma nem chama a API")
        void semSecoesNaoChama() throws Exception {
            assertTrue(narrador.narrar("Anderson", List.of()).isEmpty());

            verify(claude, never())
                    .criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
        }
    }

    @Nested
    @DisplayName("separação dos trechos")
    class Separacao {

        @Test
        @DisplayName("aceita trecho de várias linhas")
        void variasLinhas() {
            List<String> trechos = NarradorDeBriefing.separar("""
                    [0] Bom dia, senhor.
                    O briefing está pronto.
                    [1] Dezoito graus.""", 2);

            assertEquals(2, trechos.size());
            assertEquals("Bom dia, senhor.\nO briefing está pronto.", trechos.get(0));
        }

        @Test
        @DisplayName("marca faltando, sobrando ou fora de ordem devolve vazio")
        void formatoTorto() {
            // Na dúvida, texto determinístico: melhor sem enfeite do que com seção trocada.
            assertTrue(NarradorDeBriefing.separar("[0] a\n[1] b", 3).isEmpty(), "faltou uma");
            assertTrue(NarradorDeBriefing.separar("[0] a\n[1] b\n[2] c\n[3] d", 3).isEmpty(), "sobrou uma");
            assertTrue(NarradorDeBriefing.separar("[0] a\n[2] c\n[1] b", 3).isEmpty(), "fora de ordem");
            assertTrue(NarradorDeBriefing.separar("Bom dia! Aqui está.", 3).isEmpty(), "sem marca nenhuma");
            assertTrue(NarradorDeBriefing.separar("[1] b\n[2] c", 2).isEmpty(), "não começou no zero");
        }

        @Test
        @DisplayName("trecho em branco devolve vazio")
        void trechoEmBranco() {
            assertTrue(NarradorDeBriefing.separar("[0] a\n[1]   \n[2] c", 3).isEmpty());
        }
    }
}
