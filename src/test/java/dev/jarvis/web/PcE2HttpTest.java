package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.ArquivosPcService;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.tela.FalaDaTelaService;
import tools.jackson.databind.json.JsonMapper;

/** Exercita o transporte HTTP E2 sem rede e com diário isolado. */
class PcE2HttpTest {
    @TempDir Path temporaria;

    @Test void entregaAckResultadoERepeticao() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(),
                temporaria.resolve("operacoes.json"), Clock.systemUTC());
        var pc = new PcService(e2);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(pc,
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();
        var comando = ComandoPc.nova("status_pc", "", Instant.now());
        e2.enfileirar(comando, Duration.ofMinutes(1), Duration.ofMinutes(1), "telegram", 42);

        http.perform(get("/api/pc/v2/comandos"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versao").value(2))
                .andExpect(jsonPath("$.comando.id").value(comando.id()));
        assertEquals("entregue", e2.estado(comando.id()).orElseThrow().estado());
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/recebido"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/recebido"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/executando"))
                .andExpect(status().isNoContent());
        String corpo = "{\"estado\":\"concluído\",\"mensagem\":\"pronto\",\"codigoSaida\":0}";
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isNoContent());
        http.perform(get("/api/pc/v2/comandos/" + comando.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.estado").value("concluído"));
    }

    @Test void arquivoImagemEDadosSobrevivemAoReinicio() throws Exception {
        var json = JsonMapper.builder().build();
        Path diario = temporaria.resolve("operacoes.json");
        var e2 = new OperacoesPcService(json, diario, Clock.systemUTC());
        var pc = new PcService(e2);
        var arquivos = new ArquivosPcService(pc);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(pc,
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2, arquivos),
                new ArquivosPcController(arquivos)).build();

        var comandoArquivo = ComandoPc.nova("criar_arquivo", "", Instant.now());
        e2.enfileirar(comandoArquivo, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        iniciar(http, comandoArquivo.id());
        String resultadoArquivo = "{\"estado\":\"concluído\",\"mensagem\":\"demo.txt\",\"arquivo\":true}";
        http.perform(post("/api/pc/v2/comandos/" + comandoArquivo.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoArquivo))
                .andExpect(status().isConflict());
        byte[] bytes = "conteúdo de demonstração".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        http.perform(post("/api/pc/arquivos/" + comandoArquivo.id()).content(bytes))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/arquivos/" + comandoArquivo.id()).content(bytes))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comandoArquivo.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoArquivo))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comandoArquivo.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoArquivo))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comandoArquivo.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(
                        resultadoArquivo.replace("demo.txt", "outro.txt")))
                .andExpect(status().isConflict());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(arquivos.obter(comandoArquivo.id()).orElseThrow()));

        var comandoImagem = ComandoPc.nova("captura_tela", "", Instant.now());
        e2.enfileirar(comandoImagem, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        iniciar(http, comandoImagem.id());
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        String resultadoImagem = "{\"estado\":\"concluído\",\"mensagem\":\"print\",\"imagemBase64\":\""
                + Base64.getEncoder().encodeToString(png) + "\"}";
        http.perform(post("/api/pc/v2/comandos/" + comandoImagem.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoImagem))
                .andExpect(status().isNoContent());

        var comandoDados = ComandoPc.nova("listar_arquivos", "", Instant.now());
        e2.enfileirar(comandoDados, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        iniciar(http, comandoDados.id());
        String resultadoDados = "{\"estado\":\"concluído\",\"mensagem\":\"consulta concluída\","
                + "\"dados\":{\"arquivos\":[{\"nome\":\"demo.txt\"}]}}";
        http.perform(post("/api/pc/v2/comandos/" + comandoDados.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoDados))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + comandoDados.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(resultadoDados))
                .andExpect(status().isNoContent());

        var reiniciado = new OperacoesPcService(json, diario, Clock.systemUTC());
        assertTrue(reiniciado.resultado(comandoArquivo.id()).orElseThrow().arquivo());
        assertArrayEquals(png, reiniciado.resultado(comandoImagem.id()).orElseThrow().imagem());
        assertEquals("demo.txt", reiniciado.resultado(comandoDados.id()).orElseThrow()
                .dados().path("arquivos").get(0).path("nome").asText());
        assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(
                new ArquivosPcService(new PcService(reiniciado)).obter(comandoArquivo.id()).orElseThrow()));
    }

    @Test void diagnosticoDeEdicaoRecusadaEAceitoMesmoComEstadoFalhou() throws Exception {
        var json = JsonMapper.builder().build();
        var e2 = new OperacoesPcService(json, temporaria.resolve("operacoes.json"), Clock.systemUTC());
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(new PcService(e2),
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();

        var comando = ComandoPc.nova("preparar_edicao", "", Instant.now());
        e2.enfileirar(comando, Duration.ofMinutes(1), Duration.ofMinutes(1), "telegram", 42);
        iniciar(http, comando.id());

        // O agente só tem o diagnóstico a entregar quando a proposta é recusada, e ele repete esse
        // POST antes de buscar qualquer comando: um 400 aqui trava o agente para sempre.
        String recusada = "{\"estado\":\"falhou\",\"mensagem\":\"leia o arquivo antes de propor edição\","
                + "\"dados\":{\"codigoErro\":\"VALIDACAO_RECUSADA\",\"etapa\":\"preparar_edicao\"}}";
        http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(recusada))
                .andExpect(status().isNoContent());
        assertEquals("VALIDACAO_RECUSADA", e2.resultado(comando.id()).orElseThrow()
                .dados().path("codigoErro").asText());

        // O que a regra existe para barrar continua barrado: print ou arquivo só em sucesso.
        var captura = ComandoPc.nova("captura_tela", "", Instant.now());
        e2.enfileirar(captura, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        iniciar(http, captura.id());
        byte[] png = new byte[] {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        http.perform(post("/api/pc/v2/comandos/" + captura.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"falhou\",\"mensagem\":\"nao deu\",\"imagemBase64\":\""
                        + Base64.getEncoder().encodeToString(png) + "\"}"))
                .andExpect(status().isBadRequest());

        // E 'dados' numa ação que não é etapa de edição também segue recusado fora de "concluído".
        var lista = ComandoPc.nova("listar_arquivos", "", Instant.now());
        e2.enfileirar(lista, Duration.ofMinutes(1), Duration.ofMinutes(1), "hud", 42);
        iniciar(http, lista.id());
        http.perform(post("/api/pc/v2/comandos/" + lista.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"falhou\",\"mensagem\":\"nao deu\",\"dados\":{\"arquivos\":[]}}"))
                .andExpect(status().isBadRequest());
    }

    @Test void inferirAceitaCodigoDeErroEDadosGrandesSoNela() throws Exception {
        var json = JsonMapper.builder().build();
        Path diario = temporaria.resolve("operacoes.json");
        var e2 = new OperacoesPcService(json, diario, Clock.systemUTC());
        var pc = new PcService(e2);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(pc,
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();

        // A falha da inferência traz o codigoErro em dados, com estado "falhou".
        var falha = new ComandoPc(java.util.UUID.randomUUID().toString(), "inferir", "",
                Instant.now().toString(), json.createObjectNode().put("think", false));
        e2.enfileirar(falha, Duration.ofMinutes(1), Duration.ofMinutes(1), "", 0);
        iniciar(http, falha.id());
        http.perform(post("/api/pc/v2/comandos/" + falha.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"falhou\",\"mensagem\":\"sem memória\",\"dados\":{\"codigoErro\":\"sem_memoria\"}}"))
                .andExpect(status().isNoContent());
        assertEquals("sem_memoria",
                e2.resultado(falha.id()).orElseThrow().dados().path("codigoErro").asString());

        // 100 KB de resposta passam no inferir e continuam recusados numa consulta de arquivo.
        String grande = "{\"estado\":\"concluído\",\"mensagem\":\"ok\",\"dados\":{\"message\":{\"role\":"
                + "\"assistant\",\"content\":\"" + "a".repeat(100_000) + "\"}}}";
        var inferir = new ComandoPc(java.util.UUID.randomUUID().toString(), "inferir", "",
                Instant.now().toString(), json.createObjectNode());
        e2.enfileirar(inferir, Duration.ofMinutes(1), Duration.ofMinutes(1), "", 0);
        iniciar(http, inferir.id());
        http.perform(post("/api/pc/v2/comandos/" + inferir.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(grande))
                .andExpect(status().isNoContent());
        var leitura = ComandoPc.nova("ler_arquivo", "", Instant.now());
        e2.enfileirar(leitura, Duration.ofMinutes(1), Duration.ofMinutes(1), "", 0);
        iniciar(http, leitura.id());
        http.perform(post("/api/pc/v2/comandos/" + leitura.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(grande))
                .andExpect(status().isBadRequest());

        // O prompt e a resposta da inferência não vão ao diário em disco.
        String gravado = java.nio.file.Files.readString(diario);
        assertTrue(gravado.contains(leitura.id()));
        assertTrue(!gravado.contains(inferir.id()) && !gravado.contains("aaaa"),
                "inferir não é gravado no operacoes.json");
    }

    @Test void parcialDeInferirChegaAoOuvinteE410DepoisDoCancelamento() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(),
                temporaria.resolve("operacoes.json"), Clock.systemUTC());
        e2.catalogo(java.util.List.of());
        var pc = new PcService(e2);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(pc,
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();
        var inferir = new ComandoPc(java.util.UUID.randomUUID().toString(), "inferir", "",
                Instant.now().toString(), JsonMapper.builder().build().createObjectNode());
        var recebido = new StringBuilder();
        pc.inferirTransmitindo(inferir, Duration.ofMinutes(1), (inicio, texto) -> recebido.append(texto));
        iniciar(http, inferir.id());

        http.perform(post("/api/pc/v2/comandos/" + inferir.id() + "/parcial")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inicio\":0,\"texto\":\"{\\\"t\\\"\"}"))
                .andExpect(status().isNoContent());
        assertEquals("{\"t\"", recebido.toString());
        http.perform(post("/api/pc/v2/comandos/" + inferir.id() + "/parcial")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inicio\":-1,\"texto\":\"x\"}"))
                .andExpect(status().isBadRequest());

        pc.cancelar(inferir.id());
        http.perform(post("/api/pc/v2/comandos/" + inferir.id() + "/parcial")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inicio\":4,\"texto\":\"x\"}"))
                .andExpect(status().isGone());
        http.perform(post("/api/pc/v2/comandos/" + java.util.UUID.randomUUID() + "/parcial")
                .contentType(MediaType.APPLICATION_JSON).content("{\"inicio\":0,\"texto\":\"x\"}"))
                .andExpect(status().isGone());
    }

    @Test void cabecalhoIaChegaAoPcService() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(),
                temporaria.resolve("operacoes.json"), Clock.systemUTC());
        var pc = new PcService(e2);
        MockMvc http = MockMvcBuilders.standaloneSetup(new PcController(pc,
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();
        http.perform(get("/api/pc/v2/comandos").header("X-Pc-Ia", "ativo=1;ollama=1\u0007;carregado=1"))
                .andExpect(status().isNoContent());
        assertEquals("ativo=1;ollama=1;carregado=1", pc.estadoIa().orElseThrow().cabecalho());
    }

    private static void iniciar(MockMvc http, String id) throws Exception {
        http.perform(get("/api/pc/v2/comandos")).andExpect(status().isOk())
                .andExpect(jsonPath("$.comando.id").value(id));
        http.perform(post("/api/pc/v2/comandos/" + id + "/recebido"))
                .andExpect(status().isNoContent());
        http.perform(post("/api/pc/v2/comandos/" + id + "/executando"))
                .andExpect(status().isNoContent());
    }
}
