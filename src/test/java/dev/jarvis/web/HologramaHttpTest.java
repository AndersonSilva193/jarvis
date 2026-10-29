package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.holograma.ExemplosDeHolograma;
import dev.jarvis.holograma.GaleriaDeHologramas;
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
    @TempDir
    Path pasta;
    private final ProvedorDoCubo cubo = new ProvedorDoCubo();
    private GaleriaDeHologramas galeria;
    private HologramaService geracao;
    private HologramaController controller;
    private MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void montar() {
        HologramaLineParser parser = new HologramaLineParser(JsonMapper.builder().build());
        galeria = new GaleriaDeHologramas(JsonMapper.builder().build(), pasta, new ExemplosDeHolograma(), parser);
        geracao = new HologramaService(cubo, cubo,
                () -> new DisponibilidadeLocal.Situacao(false, "pc_offline"), () -> "", props,
                new PromptDeHolograma(), parser, Duration.ofSeconds(10), Duration.ofSeconds(10),
                HologramaService.PRIMEIRA_RESPOSTA, galeria);
        controller = new HologramaController(new ExemplosDeHolograma(), parser, geracao, galeria,
                Duration.ZERO, Duration.ZERO);
        mvc = MockMvcBuilders.standaloneSetup(controller).addFilters(new TokenHudFiltro(props)).build();
    }

    @AfterEach
    void fechar() {
        controller.fechar();
        geracao.fechar();
    }

    /** Um modelo que desenha sempre o mesmo cubo (de 9 faces, para caber na galeria), e conta as chamadas. */
    private static final class ProvedorDoCubo implements ProvedorDeModelo {
        final java.util.concurrent.atomic.AtomicInteger chamadas = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public RespostaDoModelo responder(PedidoAoModelo pedido) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto,
                Cancelamento cancelamento) {
            chamadas.incrementAndGet();
            aoTexto.accept("{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"um cubo\"}\n");
            String pecas = pedido.mensagens().getFirst().texto().contains("grande")
                    ? "{\"t\":\"p\",\"id\":\"face\",\"f\":\"box\",\"s\":[1,1,1],\"rep\":{\"n\":9,\"d\":[1,0,0]}}\n"
                    : "{\"t\":\"p\",\"id\":\"cubo\",\"f\":\"box\",\"s\":[1,1,1]}\n";
            aoTexto.accept(pecas + "{\"t\":\"fim\"}\n");
            return new RespostaDoModelo(List.of(), RespostaDoModelo.MotivoDeParada.FIM, null);
        }
    }

    @Test
    void continuarRecusaIdTortoENaoAchaOQueNaoExiste() throws Exception {
        String token = "Bearer token-de-teste";
        mvc.perform(get("/api/holograma/continuar").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/continuar").param("id", "../etc").header("Authorization", token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/continuar").param("id", "habcdefgh").param("recebidas", "-1")
                .header("Authorization", token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/holograma/continuar").param("id", "habcdefgh").param("recebidas", "3")
                .header("Authorization", token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/holograma/continuar").param("id", "habcdefgh")).andExpect(status().isUnauthorized());
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

    @Test
    void objetoCompletoEntraNaGaleriaEOMesmoPedidoNaoGastaModelo() throws Exception {
        String token = "Bearer token-de-teste";
        String primeiro = ateOFim(mvc.perform(get("/api/holograma/gerar").param("objeto", "Cubo grande")
                .header("Authorization", token)).andExpect(request().asyncStarted()).andReturn());
        assertTrue(primeiro.contains("\"origem\":\"modelo\",\"salvo\":true,\"miniatura\":false"), primeiro);

        // Artigo, caixa e espaço sobrando não mudam a chave.
        String segundo = ateOFim(mvc.perform(get("/api/holograma/gerar").param("objeto", "o  CUBO grande")
                .header("Authorization", token)).andExpect(request().asyncStarted()).andReturn());
        assertTrue(segundo.contains("\"origem\":\"cache\""), segundo);
        assertEquals(9, segundo.split("event:peca", -1).length - 1);
        assertEquals(1, cubo.chamadas.get());

        // Outro nível é outro objeto; novo=true força o modelo e substitui o salvo.
        ateOFim(mvc.perform(get("/api/holograma/gerar").param("objeto", "cubo grande").param("novo", "true")
                .header("Authorization", token)).andExpect(request().asyncStarted()).andReturn());
        assertEquals(2, cubo.chamadas.get());
        List<GaleriaDeHologramas.Item> itens = galeria.listar();
        assertEquals(4, itens.size(), itens.toString());
        assertEquals("cubo grande", itens.getLast().objeto());
    }

    @Test
    void objetoPequenoNaoEntraNaGaleria() throws Exception {
        String corpo = ateOFim(mvc.perform(get("/api/holograma/gerar").param("objeto", "cubo")
                .header("Authorization", "Bearer token-de-teste")).andExpect(request().asyncStarted()).andReturn());
        assertTrue(!corpo.contains("\"salvo\""), corpo);
        assertEquals(3, galeria.listar().size());
    }

    @Test
    void galeriaListaOsExemplosFixosQueNaoSeApagam() throws Exception {
        String token = "Bearer token-de-teste";
        String lista = mvc.perform(get("/api/holograma/galeria").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(lista.startsWith("[{\"id\":\"exemplo_motor_v8\""), lista);
        assertTrue(lista.contains("\"fixo\":true"), lista);

        String corpo = ateOFim(mvc.perform(get("/api/holograma/exemplo_atomo/stream").header("Authorization", token))
                .andExpect(request().asyncStarted()).andReturn());
        assertTrue(corpo.contains("\"id\":\"exemplo_atomo\""), corpo);
        assertTrue(corpo.contains("\"origem\":\"cache\""), corpo);
        assertEquals(28, corpo.split("event:peca", -1).length - 1);

        mvc.perform(delete("/api/holograma/exemplo_atomo").header("Authorization", token))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/holograma/habcdefgh").header("Authorization", token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/holograma/..%2Fetc/stream").header("Authorization", token))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/holograma/galeria")).andExpect(status().isUnauthorized());
    }

    @Test
    void apagarTiraDaGaleriaEDoCache() throws Exception {
        String token = "Bearer token-de-teste";
        ateOFim(mvc.perform(get("/api/holograma/gerar").param("objeto", "cubo grande")
                .header("Authorization", token)).andExpect(request().asyncStarted()).andReturn());
        String id = galeria.listar().getLast().id();

        mvc.perform(delete("/api/holograma/" + id).header("Authorization", token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/holograma/" + id + "/stream").header("Authorization", token))
                .andExpect(status().isNotFound());
        assertEquals(3, galeria.listar().size());
    }

    @Test
    void miniaturaSoAceitaPngDeObjetoQueExiste() throws Exception {
        String token = "Bearer token-de-teste";
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", png);
        String dataUrl = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(png.toByteArray());

        mvc.perform(post("/api/holograma/exemplo_dna/miniatura").header("Authorization", token)
                .contentType("application/json").content("{\"imagem\":\"" + dataUrl + "\"}"))
                .andExpect(status().isNoContent());
        byte[] lida = mvc.perform(get("/api/holograma/exemplo_dna/miniatura").header("Authorization", token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(png.size(), lida.length);
        assertTrue(galeria.listar().stream().anyMatch(i -> i.id().equals("exemplo_dna") && i.miniatura()));

        // SVG declarado como PNG não passa: quem decide são os bytes.
        String falso = "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString("<svg/>".getBytes());
        mvc.perform(post("/api/holograma/exemplo_dna/miniatura").header("Authorization", token)
                .contentType("application/json").content("{\"imagem\":\"" + falso + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/holograma/habcdefgh/miniatura").header("Authorization", token)
                .contentType("application/json").content("{\"imagem\":\"" + dataUrl + "\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/holograma/exemplo_motor_v8/miniatura").header("Authorization", token))
                .andExpect(status().isNotFound());
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
