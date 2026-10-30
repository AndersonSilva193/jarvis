package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.holograma.controle.BilhetesDoCanal;
import dev.jarvis.holograma.controle.PareamentosDoControle;
import dev.jarvis.holograma.controle.ValidadorDeMensagemDoControle;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * O canal de ponta a ponta, com servidor e WebSocket de verdade: bilhete → HUD → código → celular,
 * o repasse normalizado, a recusa do segundo celular, o desconectar e a medida da ida e volta.
 *
 * <p>O contexto é só o do canal (mais o filtro do token): o {@code ContextoDaAplicacaoTest} continua
 * sendo o único que sobe a aplicação inteira.
 */
@SpringBootTest(classes = CanalDoHologramaWsTest.Contexto.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CanalDoHologramaWsTest {

    private static final String TOKEN = "token-de-teste";

    @Configuration
    @EnableAutoConfiguration
    @Import({ CanalDoHologramaConfig.class, CanalDoHologramaHandler.class, CanalDoHologramaController.class,
            BilhetesDoCanal.class, PareamentosDoControle.class, ValidadorDeMensagemDoControle.class,
            TokenHudFiltro.class })
    static class Contexto {
        @Bean
        JarvisProperties props() {
            return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                    new JarvisProperties.Telegram("teste", 42), null, null, null,
                    new JarvisProperties.Hud(TOKEN, ""), null, null, null, null, null);
        }

        @Bean
        JsonMapper jsonMapper() {
            return JsonMapper.builder().build();
        }
    }

    @LocalServerPort
    int porta;

    private final JsonMapper json = JsonMapper.builder().build();

    /** Um lado do canal: guarda o que chega e como fechou. */
    private static final class Lado extends TextWebSocketHandler {
        final BlockingQueue<String> recebidas = new LinkedBlockingQueue<>();
        final CompletableFuture<CloseStatus> fechou = new CompletableFuture<>();
        WebSocketSession sessao;

        @Override
        protected void handleTextMessage(WebSocketSession s, TextMessage m) {
            recebidas.add(m.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession s, CloseStatus status) {
            fechou.complete(status);
        }

        void mandar(String texto) throws Exception {
            sessao.sendMessage(new TextMessage(texto));
        }
    }

    private JsonNode proxima(Lado lado) throws Exception {
        String texto = lado.recebidas.poll(3, TimeUnit.SECONDS);
        assertNotNull(texto, "nada chegou");
        return json.readTree(texto);
    }

    private Lado conectar(String query) throws Exception {
        Lado lado = new Lado();
        lado.sessao = new StandardWebSocketClient()
                .execute(lado, "ws://localhost:" + porta + CanalDoHologramaConfig.ROTA + "?" + query)
                .get(3, TimeUnit.SECONDS);
        return lado;
    }

    private HttpResponse<String> pedirBilhete(String token) throws Exception {
        HttpRequest.Builder pedido = HttpRequest.newBuilder(
                URI.create("http://localhost:" + porta + "/api/holograma/controle/bilhete"))
                .POST(HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(pedido.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Lado abrirHud() throws Exception {
        String bilhete = json.readTree(pedirBilhete(TOKEN).body()).path("bilhete").stringValue();
        return conectar("bilhete=" + bilhete);
    }

    @Test
    void semTokenNaoHaBilheteESemCredencialOCanalFecha() throws Exception {
        assertEquals(401, pedirBilhete(null).statusCode());
        assertEquals(4401, conectar("par=nada").fechou.get(3, TimeUnit.SECONDS).getCode());
        assertEquals(4401, conectar("").fechou.get(3, TimeUnit.SECONDS).getCode());
    }

    @Test
    void oBilheteSoAbreUmaVez() throws Exception {
        String bilhete = json.readTree(pedirBilhete(TOKEN).body()).path("bilhete").stringValue();
        conectar("bilhete=" + bilhete);
        assertEquals(4401, conectar("bilhete=" + bilhete).fechou.get(3, TimeUnit.SECONDS).getCode());
    }

    @Test
    void pareiaRepassaMedeEDesconecta() throws Exception {
        Lado hud = abrirHud();
        hud.mandar("{\"t\":\"parear\"}");
        JsonNode par = proxima(hud);
        assertEquals("par", par.path("t").stringValue());
        assertEquals(300, par.path("expira").intValue());
        String codigo = par.path("codigo").stringValue();

        Lado celular = conectar("par=" + codigo);
        JsonNode pareado = proxima(celular);
        assertEquals("pareado", pareado.path("t").stringValue());
        assertEquals(43, pareado.path("retomada").stringValue().length());
        JsonNode conectado = proxima(hud);
        assertEquals("conectado", conectado.path("e").stringValue());
        assertEquals(43, conectado.path("retomada").stringValue().length());

        // O segundo celular com o mesmo código: já foi usado.
        assertEquals(4401, conectar("par=" + codigo).fechou.get(3, TimeUnit.SECONDS).getCode());

        // Repasse: normalizado, e o torto não chega.
        celular.mandar("{\"t\":\"imagem\",\"dados\":\"iVBOR\"}");
        celular.mandar("{\"dy\":-0.01,\"t\":\"girar\",\"dx\":0.02}");
        assertEquals("{\"t\":\"girar\",\"dx\":0.02,\"dy\":-0.01}", hud.recebidas.poll(3, TimeUnit.SECONDS));
        celular.mandar("[{\"t\":\"cursor\",\"x\":0.4,\"y\":0.7},{\"t\":\"selecionar\"}]");
        assertEquals("[{\"t\":\"cursor\",\"x\":0.4,\"y\":0.7},{\"t\":\"selecionar\"}]",
                hud.recebidas.poll(3, TimeUnit.SECONDS));
        // A latência fica no servidor.
        celular.mandar("{\"t\":\"latencia\",\"p50\":10,\"p95\":20,\"k\":5}");
        assertNull(hud.recebidas.poll(200, TimeUnit.MILLISECONDS));

        // Ida e volta pelo caminho inteiro: celular → servidor → HUD → servidor → celular.
        List<Double> ms = new ArrayList<>();
        for (int n = 0; n < 200; n++) {
            long inicio = System.nanoTime();
            celular.mandar("{\"t\":\"eco\",\"n\":" + n + "}");
            hud.mandar(hud.recebidas.poll(3, TimeUnit.SECONDS));
            assertEquals(n, proxima(celular).path("n").intValue());
            ms.add((System.nanoTime() - inicio) / 1e6);
            Thread.sleep(25); // abaixo dos 40/s do balde
        }
        ms.sort(Double::compare);
        double p50 = ms.get(ms.size() / 2);
        double p95 = ms.get((int) Math.ceil(ms.size() * 0.95) - 1);
        System.out.printf("Canal do holograma (local): ida e volta p50=%.2f ms p95=%.2f ms (%d amostras)%n",
                p50, p95, ms.size());
        assertTrue(p95 < 100, "local tem de ficar bem abaixo dos 150 ms: " + p95);

        // Com celular ligado, a HUD não gera outro código.
        hud.mandar("{\"t\":\"parear\"}");
        assertEquals("ocupado", proxima(hud).path("e").stringValue());

        hud.mandar("{\"t\":\"desconectar\"}");
        assertEquals("encerrado", proxima(hud).path("e").stringValue());
        assertEquals(4000, celular.fechou.get(3, TimeUnit.SECONDS).getCode());
    }

    @Test
    void oCelularQueCaiVoltaComARetomadaEAHudFicaSabendo() throws Exception {
        Lado hud = abrirHud();
        hud.mandar("{\"t\":\"parear\"}");
        Lado celular = conectar("par=" + proxima(hud).path("codigo").stringValue());
        String retomada = proxima(celular).path("retomada").stringValue();
        proxima(hud);

        celular.sessao.close(new CloseStatus(4001, "rede caiu"));
        assertEquals("caiu", proxima(hud).path("e").stringValue());

        Lado volta = conectar("retomada=" + retomada);
        assertEquals("pareado", proxima(volta).path("t").stringValue());
        assertEquals("voltou", proxima(hud).path("e").stringValue());

        // O "Sair" do celular (1000) acaba o par na hora.
        volta.sessao.close(CloseStatus.NORMAL);
        assertEquals("encerrado", proxima(hud).path("e").stringValue());
        assertEquals(4401, conectar("retomada=" + retomada).fechou.get(3, TimeUnit.SECONDS).getCode());
    }

    @Test
    void enxurradaFechaOCelular() throws Exception {
        Lado hud = abrirHud();
        hud.mandar("{\"t\":\"parear\"}");
        Lado celular = conectar("par=" + proxima(hud).path("codigo").stringValue());
        try {
            for (int i = 0; i < 400 && celular.sessao.isOpen(); i++) {
                celular.mandar("{\"t\":\"resetar\"}");
            }
        } catch (Exception fechouNoMeio) {
            // O servidor fechou enquanto o laço ainda escrevia: é o que se quer ver.
        }
        assertEquals(CloseStatus.POLICY_VIOLATION.getCode(), celular.fechou.get(3, TimeUnit.SECONDS).getCode());
    }
}
