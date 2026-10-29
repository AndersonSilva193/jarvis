package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.holograma.ExemplosDeHolograma;
import dev.jarvis.holograma.HologramaLineParser;
import dev.jarvis.holograma.HologramaService;
import dev.jarvis.holograma.PromptDeHolograma;
import dev.jarvis.ia.provedor.Cancelamento;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/** O exemplo pelo SSE, atrás do token da HUD: meta primeiro, as peças, e o fim fechando. */
class HologramaHttpTest {

    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null, null);
    private final HologramaService geracao = new HologramaService(new ProvedorDoCubo(), new ProvedorDoCubo(),
            () -> new DisponibilidadeLocal.Situacao(false, "pc_offline"), props, new PromptDeHolograma(),
            new HologramaLineParser(JsonMapper.builder().build()), Duration.ofSeconds(10), Duration.ofSeconds(10));
    private final HologramaController controller = new HologramaController(new ExemplosDeHolograma(),
            new HologramaLineParser(JsonMapper.builder().build()), geracao, Duration.ZERO);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(controller)
            .addFilters(new TokenHudFiltro(props)).build();

    @AfterEach
    void fechar() {
        controller.fechar();
        geracao.fechar();
    }

    /** Um modelo que desenha sempre o mesmo cubo, num delta por linha. */
    private static final class ProvedorDoCubo implements ProvedorDeModelo {
        @Override
        public RespostaDoModelo responder(PedidoAoModelo pedido) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto,
                Cancelamento cancelamento) {
            aoTexto.accept("{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"um cubo\"}\n");
            aoTexto.accept("{\"t\":\"p\",\"id\":\"cubo\",\"f\":\"box\",\"s\":[1,1,1]}\n{\"t\":\"fim\"}\n");
            return new RespostaDoModelo(List.of(), RespostaDoModelo.MotivoDeParada.FIM, null);
        }
    }

    @Test
    void gerarRecusaParametroTorto() throws Exception {
        String token = "Bearer token-de-teste";
        mvc.perform(get("/api/holograma/gerar").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/gerar").param("objeto", "   ").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/gerar").param("objeto", "x".repeat(121)).header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/gerar").param("objeto", "motor").param("detalhe", "maximo")
                .header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/gerar").param("objeto", "motor").param("provedor", "gpt")
                .header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/gerar").param("objeto", "motor")).andExpect(status().isUnauthorized());
    }

    @Test
    void gerarAceitaOProvedorDoPedido() throws Exception {
        var resultado = mvc.perform(get("/api/holograma/gerar").param("objeto", "cubo").param("provedor", "haiku")
                .header("Authorization", "Bearer token-de-teste"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String corpo = ateOFim(resultado);
        assertTrue(corpo.contains("\"provedor\":\"anthropic\""), corpo);
    }

    @Test
    void gerarTransmiteSemBufferNoNginx() throws Exception {
        var resultado = mvc.perform(get("/api/holograma/gerar").param("objeto", "cubo")
                .header("Authorization", "Bearer token-de-teste"))
                .andExpect(request().asyncStarted())
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andReturn();

        String corpo = ateOFim(resultado);
        assertTrue(corpo.startsWith("event:meta"), corpo);
        assertTrue(corpo.contains("\"pecas\":1,\"avisos\":0"), corpo);
    }

    @Test
    void semTokenNemQueryToken() throws Exception {
        mvc.perform(get("/api/holograma/exemplo/atomo")).andExpect(status().isUnauthorized());
        // O ?token= é exceção do SSE geral, de anexos e de áudio — não desta rota.
        mvc.perform(get("/api/holograma/exemplo/atomo").param("token", "token-de-teste"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exemploQueNaoExiste() throws Exception {
        mvc.perform(get("/api/holograma/exemplo/nao_existe").header("Authorization", "Bearer token-de-teste"))
                .andExpect(status().isNotFound());
    }

    @Test
    void transmiteOExemploEmOrdem() throws Exception {
        var resultado = mvc.perform(get("/api/holograma/exemplo/atomo")
                .header("Authorization", "Bearer token-de-teste"))
                .andExpect(request().asyncStarted()).andReturn();

        String corpo = ateOFim(resultado);
        assertTrue(corpo.startsWith("event:meta"), corpo);
        assertTrue(corpo.contains("\"nome\":\"Átomo de carbono\""), corpo);
        assertTrue(corpo.contains("\"id\":\"orbita_2\""), corpo);
        assertTrue(!corpo.contains("event:aviso"), corpo);

        int pecas = corpo.split("event:peca", -1).length - 1;
        assertTrue(corpo.contains("\"pecas\":" + pecas + ",\"avisos\":0"), corpo);
        // 4 peças soltas, 2 grupos de 3 órbitas e 6 grupos de 3 partículas, mais o campo do núcleo.
        assertEquals(28, pecas);
    }

    /**
     * O corpo depois do {@code fim} inteiro. Esperar só o {@code event:fim} aparecer é uma corrida:
     * o nome do evento e o {@code data:} são escritos separados, e o teste lia o placar antes dele.
     */
    private static String ateOFim(org.springframework.test.web.servlet.MvcResult resultado) {
        return assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (;;) {
                String corpo = resultado.getResponse().getContentAsString(StandardCharsets.UTF_8);
                int fim = corpo.indexOf("event:fim");
                if (fim >= 0 && corpo.indexOf("\n\n", fim) >= 0) return corpo;
                Thread.sleep(5);
            }
        });
    }
}
