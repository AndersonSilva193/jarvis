package dev.jarvis.busca;

import static dev.jarvis.busca.HttpDublado.corpo;
import static dev.jarvis.busca.HttpDublado.pedidos;
import static dev.jarvis.busca.HttpDublado.props;
import static dev.jarvis.busca.HttpDublado.respondendo;
import static dev.jarvis.busca.HttpDublado.resposta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import dev.jarvis.busca.ProvedorDeBusca.Consulta;
import dev.jarvis.busca.ProvedorDeBusca.FalhaDeBusca;
import dev.jarvis.busca.ProvedorDeBusca.RespostaDaBusca;
import dev.jarvis.custo.Origem;
import dev.jarvis.ia.BuscaWebServidor;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ConversorAnthropic;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Cada provedor contra o HTTP (ou o modelo) dublado: o pedido que sai e a leitura do que volta. */
class ProvedoresDeBuscaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String TAVILY_OK = """
            {"query":"selic hoje","response_time":0.8,"results":[
              {"title":"Copom mantém a Selic","url":"https://g1.globo.com/selic","content":"O Copom manteve a taxa.","score":0.9,"published_date":"2026-10-27"},
              {"title":"Sem data","url":"https://exemplo.com.br/a","content":"Outro trecho.","score":0.5}
            ]}""";

    @Test
    @DisplayName("Tavily: POST com Bearer, profundidade básica, recência em start_date, e lê results")
    void tavily() throws Exception {
        HttpClient http = respondendo(resposta(200, TAVILY_OK));
        var tavily = new TavilyBusca(http, JSON, props("tavily", "tvly-segredo", "", 1000),
                new RelogioFalso("2026-10-28T15:00:00Z"));

        RespostaDaBusca achado = tavily.buscar(new Consulta("selic hoje", 3, 7));

        HttpRequest pedido = pedidos(http).getFirst();
        assertEquals("https://api.tavily.com/search", pedido.uri().toString());
        assertEquals("POST", pedido.method());
        assertEquals("Bearer tvly-segredo", pedido.headers().firstValue("Authorization").orElseThrow());
        JsonNode corpo = JSON.readTree(corpo(pedido));
        assertEquals("selic hoje", corpo.path("query").stringValue());
        assertEquals("basic", corpo.path("search_depth").stringValue(), "1 crédito, não 2");
        assertEquals(3, corpo.path("max_results").intValue());
        assertTrue(corpo.path("include_published_date").booleanValue());
        assertEquals("2026-10-21", corpo.path("start_date").stringValue());

        assertEquals("tavily", achado.provedor());
        assertEquals(2, achado.resultados().size());
        assertEquals("Copom mantém a Selic", achado.resultados().getFirst().titulo());
        assertEquals("2026-10-27", achado.resultados().getFirst().data());
        assertEquals("", achado.resultados().get(1).data());
    }

    @Test
    @DisplayName("Tavily: HTTP 432 vira falha com o código, sem a chave na mensagem")
    void tavilyFalha() throws Exception {
        var tavily = new TavilyBusca(respondendo(resposta(432, "{\"detail\":\"limit\"}")), JSON,
                props("tavily", "tvly-segredo", "", 1000));
        FalhaDeBusca falha = assertThrows(FalhaDeBusca.class,
                () -> tavily.buscar(new Consulta("x", 5, 0)));
        assertEquals("http_432", falha.motivo());
        assertFalse(falha.getMessage().contains("segredo"));
    }

    @Test
    @DisplayName("Tavily sem chave não está configurado")
    void tavilySemChave() {
        assertFalse(new TavilyBusca(mock(HttpClient.class), JSON, props("tavily", "", "", 1000)).configurado());
    }

    @Test
    @DisplayName("Tavily: extração lê raw_content, e só failed_results vira falha")
    void tavilyExtrai() throws Exception {
        HttpClient http = respondendo(resposta(200, """
                {"results":[{"url":"https://g1.globo.com/selic","raw_content":"Texto da página."}],"failed_results":[]}"""));
        var tavily = new TavilyBusca(http, JSON, props("tavily", "tvly-x", "", 1000));
        assertEquals("Texto da página.", tavily.extrair("https://g1.globo.com/selic"));
        JsonNode corpo = JSON.readTree(corpo(pedidos(http).getFirst()));
        assertEquals("https://g1.globo.com/selic", corpo.path("urls").get(0).stringValue());
        assertEquals("basic", corpo.path("extract_depth").stringValue());

        var falhou = new TavilyBusca(respondendo(resposta(200, """
                {"results":[],"failed_results":[{"url":"https://a.com","error":"timeout"}]}""")),
                JSON, props("tavily", "tvly-x", "", 1000));
        assertEquals("extracao_falhou",
                assertThrows(FalhaDeBusca.class, () -> falhou.extrair("https://a.com")).motivo());
    }

    @Test
    @DisplayName("SearXNG: GET no formato json, faixa de tempo e corte na quantidade")
    void searxng() throws Exception {
        HttpClient http = respondendo(resposta(200, """
                {"query":"selic","results":[
                  {"title":"A","url":"https://a.com.br/1","content":"um","publishedDate":"2026-10-26T10:00:00"},
                  {"title":"B","url":"https://b.com.br/2","content":"dois"},
                  {"title":"C","url":"https://c.com.br/3","content":"três"}
                ]}"""));
        var searxng = new SearxngBusca(http, JSON, props("searxng", "", "http://searxng:8080/", 1000));

        RespostaDaBusca achado = searxng.buscar(new Consulta("selic hoje", 2, 3));

        String uri = pedidos(http).getFirst().uri().toString();
        assertTrue(uri.startsWith("http://searxng:8080/search?q=selic+hoje"), uri);
        assertTrue(uri.contains("format=json"));
        assertTrue(uri.contains("time_range=week"));
        assertEquals(2, achado.resultados().size());
        assertEquals("2026-10-26T10:00:00", achado.resultados().getFirst().data());
        assertEquals("", SearxngBusca.faixaDeTempo(0));
        assertEquals("day", SearxngBusca.faixaDeTempo(1));
        assertEquals("year", SearxngBusca.faixaDeTempo(90));
    }

    @Test
    @DisplayName("SearXNG sem o json ligado (403) é falha, para a reserva assumir")
    void searxngSemJson() throws Exception {
        var searxng = new SearxngBusca(respondendo(resposta(403, "Forbidden")), JSON,
                props("searxng", "", "http://searxng:8080", 1000));
        assertEquals("http_403",
                assertThrows(FalhaDeBusca.class, () -> searxng.buscar(new Consulta("x", 5, 0))).motivo());
    }

    @Test
    @DisplayName("Anthropic: chamada curta de origem BUSCA, só com a nativa, fontes dos blocos da API")
    void anthropic() throws Exception {
        ProvedorDeModelo modelo = mock(ProvedorDeModelo.class);
        JsonNode resultado = JSON.readTree("""
                {"type":"web_search_tool_result","tool_use_id":"srv_1","content":[
                  {"type":"web_search_result","title":"Copom","url":"https://g1.globo.com/selic","page_age":"27 de outubro de 2026","encrypted_content":"..."}
                ]}""");
        when(modelo.responder(any(PedidoAoModelo.class))).thenReturn(new RespostaDoModelo(
                List.of(Bloco.Opaco.de(ConversorAnthropic.PROVEDOR, resultado),
                        new Bloco.Texto("A Selic ficou em 10%.")),
                RespostaDoModelo.MotivoDeParada.FIM, RespostaDoModelo.Uso.nenhum()));
        var nativa = new BuscaWebServidor(props("anthropic", "", "", 0), JSON);

        RespostaDaBusca achado = new AnthropicBusca(modelo, nativa).buscar(new Consulta("selic", 5, 0));

        ArgumentCaptor<PedidoAoModelo> pedido = ArgumentCaptor.forClass(PedidoAoModelo.class);
        Mockito.verify(modelo).responder(pedido.capture());
        assertEquals(Origem.BUSCA, pedido.getValue().opcoes().origem());
        assertTrue(pedido.getValue().opcoes().curta());
        assertTrue(pedido.getValue().ferramentas().definicoes().isEmpty(), "só a busca nativa");
        assertEquals(1, pedido.getValue().ferramentas().doProvedor().getFirst().bruto()
                .path("max_uses").intValue());
        assertEquals("anthropic", achado.provedor());
        assertEquals("https://g1.globo.com/selic", achado.resultados().getFirst().url());
        assertEquals("27 de outubro de 2026", achado.resultados().getFirst().data());
        assertEquals("A Selic ficou em 10%.", achado.resumo());
    }

    @Test
    @DisplayName("Anthropic sem nenhuma fonte é falha: texto sem busca não é resultado de busca")
    void anthropicSemFonte() throws Exception {
        ProvedorDeModelo modelo = mock(ProvedorDeModelo.class);
        when(modelo.responder(any(PedidoAoModelo.class))).thenReturn(new RespostaDoModelo(
                List.of(new Bloco.Texto("Acho que é 10%.")), RespostaDoModelo.MotivoDeParada.FIM,
                RespostaDoModelo.Uso.nenhum()));
        var busca = new AnthropicBusca(modelo, new BuscaWebServidor(props("anthropic", "", "", 0), JSON));
        assertEquals("sem_resultado",
                assertThrows(FalhaDeBusca.class, () -> busca.buscar(new Consulta("selic", 5, 0))).motivo());
        assertFalse(new AnthropicBusca(modelo, BuscaWebServidor.desligada()).configurado());
    }
}
