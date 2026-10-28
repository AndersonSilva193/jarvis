package dev.jarvis.busca;

import static dev.jarvis.busca.HttpDublado.props;
import static dev.jarvis.busca.HttpDublado.respondendo;
import static dev.jarvis.busca.HttpDublado.resposta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.busca.ProvedorDeBusca.Consulta;
import dev.jarvis.busca.ProvedorDeBusca.FalhaDeBusca;
import dev.jarvis.busca.ProvedorDeBusca.RespostaDaBusca;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** A ordem, a reserva, o filtro de URL, o cache e a cota, com os provedores reais sobre HTTP dublado. */
class BuscaNaWebTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String SEARXNG_OK = """
            {"results":[{"title":"Do SearXNG","url":"https://searx.exemplo.com.br/1","content":"trecho"}]}""";
    private static final String TAVILY_OK = """
            {"results":[{"title":"Do Tavily","url":"https://tavily.exemplo.com.br/1","content":"trecho","published_date":"2026-10-27"}]}""";

    private final CotaDeBusca cota = mock(CotaDeBusca.class);
    private final RelogioFalso relogio = new RelogioFalso("2026-10-28T15:00:00Z");

    private BuscaNaWeb busca(JarvisProperties props, HttpClient tavily, HttpClient searxng) {
        return new BuscaNaWeb(List.of(new TavilyBusca(tavily, JSON, props),
                new SearxngBusca(searxng, JSON, props)), cota, props, relogio);
    }

    @Test
    @DisplayName("o primeiro falhou, o próximo da lista responde — e só a busca que deu certo conta")
    void reserva() throws Exception {
        var props = props("tavily,searxng", "tvly-x", "http://searxng:8080", 1000);
        HttpClient tavily = respondendo(resposta(500, "erro"));
        HttpClient searxng = respondendo(resposta(200, SEARXNG_OK));

        RespostaDaBusca achado = busca(props, tavily, searxng).buscar(new Consulta("selic", 5, 0));

        assertEquals("searxng", achado.provedor());
        assertEquals("Do SearXNG", achado.resultados().getFirst().titulo());
        verify(cota, never()).registrar("tavily");
        verify(cota).registrar("searxng");
    }

    @Test
    @DisplayName("provedor sem chave é pulado sem nem ser chamado")
    void semChavePulado() throws Exception {
        var props = props("tavily,searxng", "", "http://searxng:8080", 1000);
        HttpClient tavily = mock(HttpClient.class);
        HttpClient searxng = respondendo(resposta(200, SEARXNG_OK));

        assertEquals("searxng", busca(props, tavily, searxng).buscar(new Consulta("selic", 5, 0)).provedor());
        verifyNoInteractions(tavily);
    }

    @Test
    @DisplayName("todos falhando: a falha conta o motivo de cada um; nenhum configurado é sem_provedor")
    void todosFalham() throws Exception {
        var props = props("tavily,searxng", "tvly-x", "http://searxng:8080", 1000);
        FalhaDeBusca falha = assertThrows(FalhaDeBusca.class, () -> busca(props,
                respondendo(resposta(432, "")), respondendo(resposta(403, ""))).buscar(new Consulta("x", 5, 0)));
        assertEquals("tavily: http_432; searxng: http_403", falha.motivo());

        var nenhum = props("tavily,searxng", "", "", 1000);
        assertEquals("sem_provedor", assertThrows(FalhaDeBusca.class, () -> busca(nenhum,
                mock(HttpClient.class), mock(HttpClient.class)).buscar(new Consulta("x", 5, 0))).motivo());
    }

    @Test
    @DisplayName("URL que não é https segura é descartada; só https entra")
    void soHttps() throws Exception {
        var props = props("tavily", "tvly-x", "", 1000);
        HttpClient tavily = respondendo(resposta(200, """
                {"results":[
                  {"title":"http","url":"http://inseguro.com.br/a","content":"a"},
                  {"title":"script","url":"javascript:alert(1)","content":"b"},
                  {"title":"senha","url":"https://user:senha@exemplo.com.br/c","content":"c"},
                  {"title":"ok","url":"https://seguro.com.br/d","content":"d"},
                  {"title":"repetida","url":"https://seguro.com.br/d","content":"e"}
                ]}"""));

        RespostaDaBusca achado = busca(props, tavily, mock(HttpClient.class)).buscar(new Consulta("x", 5, 0));

        assertEquals(1, achado.resultados().size());
        assertEquals("https://seguro.com.br/d", achado.resultados().getFirst().url());
        assertNull(BuscaNaWeb.urlAceita("ftp://exemplo.com.br/x"));
    }

    @Test
    @DisplayName("sem nenhum resultado https, conta como falha e vai ao próximo")
    void semResultadoVaiAoProximo() throws Exception {
        var props = props("tavily,searxng", "tvly-x", "http://searxng:8080", 1000);
        HttpClient tavily = respondendo(resposta(200, """
                {"results":[{"title":"http","url":"http://inseguro.com.br/a","content":"a"}]}"""));
        assertEquals("searxng", busca(props, tavily, respondendo(resposta(200, SEARXNG_OK)))
                .buscar(new Consulta("x", 5, 0)).provedor());
    }

    @Test
    @DisplayName("a mesma consulta normalizada sai do cache por 10 minutos, e depois volta ao provedor")
    @SuppressWarnings("unchecked")
    void cache() throws Exception {
        var props = props("tavily", "tvly-x", "", 1000);
        HttpClient tavily = respondendo(resposta(200, TAVILY_OK));
        BuscaNaWeb busca = busca(props, tavily, mock(HttpClient.class));

        busca.buscar(new Consulta("Eleição  2026", 5, 0));
        RespostaDaBusca segunda = busca.buscar(new Consulta("eleicao 2026", 5, 0));
        assertEquals("Do Tavily", segunda.resultados().getFirst().titulo());
        verify(tavily, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(cota, times(1)).registrar("tavily");

        busca.buscar(new Consulta("eleicao 2026", 3, 0));
        verify(tavily, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        relogio.avancar(Duration.ofMinutes(10));
        busca.buscar(new Consulta("eleicao 2026", 5, 0));
        verify(tavily, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("com a cota do Tavily esgotada, ele é pulado até a virada")
    void cotaEsgotadaPula() throws Exception {
        var props = props("tavily,searxng", "tvly-x", "http://searxng:8080", 1000);
        when(cota.esgotada("tavily")).thenReturn(true);
        HttpClient tavily = mock(HttpClient.class);

        RespostaDaBusca achado = busca(props, tavily, respondendo(resposta(200, SEARXNG_OK)))
                .buscar(new Consulta("x", 5, 0));

        assertEquals("searxng", achado.provedor());
        verifyNoInteractions(tavily);
    }

    @Test
    @DisplayName("a ordem só tem os configurados; sem chave, a Anthropic vira a primeira")
    void ordemDosConfigurados() {
        assertEquals(List.of("tavily", "searxng", "anthropic"),
                props("tavily,searxng,anthropic", "tvly-x", "http://s", 1000).provedoresDeBusca());
        assertEquals(List.of("anthropic"), props(null, "", "", 1000).provedoresDeBusca());
        assertEquals(List.of("searxng"), props("SearXNG, nada, searxng", "", "http://s", 1000)
                .provedoresDeBusca(), "nome desconhecido e repetido saem");
        assertTrue(props("anthropic,tavily", "tvly-x", "", 1000).buscaNativaNaConversa());
    }
}
