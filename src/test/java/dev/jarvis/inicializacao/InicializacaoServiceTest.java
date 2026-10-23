package dev.jarvis.inicializacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.custo.Origem;
import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.ClimaService.ClimaHoje;
import dev.jarvis.briefing.fontes.ServidorService;
import dev.jarvis.briefing.fontes.ServidorService.EstadoServidor;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.briefing.fontes.SitesService.Checagem;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.lembretes.LembreteService.Lembrete;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A abertura falada: o que entra no prompt, o que acontece quando uma fonte cai ou trava, e o
 * cache que impede palmas repetidas de virarem duas chamadas ao Claude.
 */
class InicializacaoServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String FALA_DO_CLAUDE = "Boa tarde, senhor. Tudo operacional. Às suas ordens.";
    /** Curto de propósito: o teste da fonte travada não tem por que esperar três segundos. */
    private static final Duration PRAZO_CURTO = Duration.ofMillis(150);

    private final ClimaService clima = mock(ClimaService.class);
    private final SitesService sites = mock(SitesService.class);
    private final ServidorService servidor = mock(ServidorService.class);
    private final LembreteService lembretes = mock(LembreteService.class);
    private final ClaudeClient claude = mock(ClaudeClient.class);
    private final SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
    private final RelogioFalso relogio = new RelogioFalso();

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token", ""), null, null, null, null,
            new JarvisProperties.Pc(""));

    private InicializacaoService servico() {
        return new InicializacaoService(clima, sites, servidor, lembretes, claude, sistema, JSON,
                props, relogio, PRAZO_CURTO);
    }

    @BeforeEach
    void prepararFontes() throws Exception {
        ArrayNode blocos = JSON.createArrayNode();
        blocos.addObject().put("type", "text").put("text", "personalidade");
        when(sistema.blocos(any(Canal.class))).thenReturn(blocos);

        when(clima.hoje()).thenReturn(
                new ClimaHoje("Curitiba", 22.4, 15, 24, 10, 8, "céu limpo"));
        when(sites.checarTodos()).thenReturn(List.of(
                new Checagem("loja", "https://loja.example", "HTTP 200 em 80 ms (OK)"),
                new Checagem("api", "https://api.example", "HTTP 200 em 95 ms (OK)")));
        when(servidor.estado()).thenReturn(
                new EstadoServidor(4, 0.4, 0.2, 2L << 30, 8L << 30, 20L << 30, 100L << 30,
                        80L << 30, "3 dias"));
        // Meio-dia do dia do relógio falso, não "agora + 2 h": perto da meia-noite, as duas horas
        // cairiam no dia seguinte e o lembrete sairia da conta de hoje.
        ZonedDateTime hoje = relogio.instant().atZone(props.zona())
                .truncatedTo(ChronoUnit.DAYS).plusHours(12);
        when(lembretes.listar()).thenReturn(List.of(new Lembrete(1, "dentista", hoje)));

        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenReturn(resposta(FALA_DO_CLAUDE));
    }

    private static JsonNode resposta(String texto) {
        ObjectNode r = JSON.createObjectNode();
        r.put("stop_reason", "end_turn");
        r.putArray("content").addObject().put("type", "text").put("text", texto);
        return r;
    }

    /** O texto que foi parar no prompt do usuário, para conferir o que o modelo recebeu. */
    private String pedidoEnviado() throws Exception {
        ArgumentCaptor<ArrayNode> mensagens = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(Origem.class), any(ArrayNode.class), mensagens.capture(),
                any(ArrayNode.class));
        return mensagens.getValue().get(0).path("content").asString();
    }

    @Test
    @DisplayName("com tudo de pé, fala o texto do Claude e conta sites e lembretes")
    void tudoDePe() throws Exception {
        Introducao introducao = servico().gerar();

        assertEquals(FALA_DO_CLAUDE, introducao.texto());
        assertEquals(2, introducao.sitesOk());
        assertEquals(2, introducao.sitesTotal());
        assertTrue(introducao.climaOk());
        assertEquals(1, introducao.lembretesHoje());

        String pedido = pedidoEnviado();
        assertTrue(pedido.contains("22 graus em Curitiba"), pedido);
        assertTrue(pedido.contains("2 de 2 no ar"), pedido);
    }

    @Test
    @DisplayName("usa o canal hud e não oferece ferramenta nenhuma")
    void canalHudSemFerramentas() throws Exception {
        servico().gerar();

        verify(sistema).blocos(Canal.HUD);
        ArgumentCaptor<ArrayNode> ferramentas = ArgumentCaptor.forClass(ArrayNode.class);
        verify(claude).criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                ferramentas.capture());
        // Mandar ferramenta aqui abriria a porta para o modelo sair chamando coisa no meio de uma
        // apresentação de três frases.
        assertTrue(ferramentas.getValue().isEmpty());
    }

    @Test
    @DisplayName("fonte fora do ar fica de fora, sem derrubar a introdução")
    void fonteForaDoArNaoDerruba() throws Exception {
        when(clima.hoje()).thenThrow(new IOException("open-meteo recusou"));

        Introducao introducao = servico().gerar();

        assertEquals(FALA_DO_CLAUDE, introducao.texto());
        assertFalse(introducao.climaOk(), "a tela precisa saber que o clima falhou");
        assertEquals(2, introducao.sitesOk(), "as outras fontes continuam valendo");
        assertFalse(pedidoEnviado().contains("clima:"), "sem dado, sem linha no prompt");
    }

    @Test
    @DisplayName("fonte que trava é abandonada no prazo, e o resto da introdução sai")
    void fonteLentaEAbandonada() throws Exception {
        when(sites.checarTodos()).thenAnswer(invocacao -> {
            Thread.sleep(Duration.ofSeconds(30));
            return List.of();
        });

        long comecou = System.nanoTime();
        Introducao introducao = servico().gerar();
        Duration levou = Duration.ofNanos(System.nanoTime() - comecou);

        assertTrue(levou.compareTo(Duration.ofSeconds(5)) < 0,
                "o despertar não pode ficar refém do site mais lento; levou " + levou);
        assertEquals(0, introducao.sitesTotal());
        assertTrue(introducao.climaOk(), "quem respondeu a tempo continua na introdução");
    }

    @Test
    @DisplayName("problema em um site vira a primeira coisa que o Jarvis fala")
    void problemaVemPrimeiro() throws Exception {
        when(sites.checarTodos()).thenReturn(List.of(
                new Checagem("loja", "https://loja.example", "HTTP 200 em 80 ms (OK)"),
                new Checagem("api", "https://api.example", "FORA DO AR ou inacessível")));

        Introducao introducao = servico().gerar();

        assertEquals(1, introducao.sitesOk());
        assertEquals(2, introducao.sitesTotal());
        String pedido = pedidoEnviado();
        assertTrue(pedido.contains("comece pelos problemas"), pedido);
        assertTrue(pedido.contains("api está fora do ar"), pedido);
    }

    @Test
    @DisplayName("Claude fora do ar cai na frase montada em Java, com os dados que chegaram")
    void claudeForaDoArCaiNaReserva() throws Exception {
        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class)))
                .thenThrow(new IOException("API do Claude respondeu HTTP 529"));

        Introducao introducao = servico().gerar();

        // A tela acabou de acender: ficar muda seria pior que uma frase sem charme.
        assertTrue(introducao.texto().contains("22 graus em Curitiba"), introducao.texto());
        assertTrue(introducao.texto().contains("Um lembrete para hoje"), introducao.texto());
        assertTrue(introducao.texto().endsWith("Às suas ordens."), introducao.texto());
    }

    @Test
    @DisplayName("duas palmas dentro de dois minutos geram uma única chamada ao Claude")
    void cacheEvitaSegundaChamada() throws Exception {
        InicializacaoService servico = servico();

        Introducao primeira = servico.gerar();
        relogio.avancar(Duration.ofSeconds(90));
        Introducao segunda = servico.gerar();

        assertSame(primeira, segunda);
        verify(claude, times(1))
                .criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                        any(ArrayNode.class));

        // Passados os dois minutos, a introdução é refeita: a hora e o clima envelhecem.
        relogio.avancar(Duration.ofMinutes(2));
        servico.gerar();
        verify(claude, times(2))
                .criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                        any(ArrayNode.class));
    }
}
