package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.tela.FalaDaTelaService;
import tools.jackson.databind.json.JsonMapper;

/** Um 400 no resultado travaria o laço do agente, então cada forma válida é conferida aqui. */
class PcEmpresaHttpTest {
    @TempDir Path temporaria;

    private OperacoesPcService e2;
    private MockMvc http;

    private ComandoPc emExecucao(String acao) throws Exception {
        e2 = new OperacoesPcService(JsonMapper.builder().build(), temporaria.resolve("operacoes.json"), Clock.systemUTC());
        http = MockMvcBuilders.standaloneSetup(new PcController(new PcService(e2),
                new FalaDaTelaService(evento -> { }, () -> 0), Duration.ZERO, e2)).build();
        var comando = ComandoPc.nova(acao, "", Instant.now());
        e2.enfileirar(comando, Duration.ofMinutes(1), Duration.ofHours(1), "telegram", 42);
        e2.proximo(Duration.ofMillis(50));
        e2.recebido(comando.id());
        e2.executando(comando.id());
        return comando;
    }

    private org.springframework.test.web.servlet.ResultActions enviar(ComandoPc comando, String corpo) throws Exception {
        return http.perform(post("/api/pc/v2/comandos/" + comando.id() + "/resultado")
                .contentType(MediaType.APPLICATION_JSON).content(corpo));
    }

    @Test void delegarConcluidoAceitaResumo() throws Exception {
        var comando = emExecucao("empresa_delegar");
        enviar(comando, "{\"estado\":\"concluído\",\"mensagem\":\"tarefa concluída\",\"dados\":{\"resumo\":\"ok\"}}")
                .andExpect(status().isNoContent());
        assertEquals("concluído", e2.estado(comando.id()).orElseThrow().estado());
    }

    @Test void delegarQueFalhouAceitaOMotivo() throws Exception {
        var comando = emExecucao("empresa_delegar");
        enviar(comando, "{\"estado\":\"falhou\",\"mensagem\":\"sem resposta\"}").andExpect(status().isNoContent());
    }

    @Test void statusAceitaDados() throws Exception {
        var comando = emExecucao("empresa_status");
        enviar(comando, "{\"estado\":\"concluído\",\"mensagem\":\"consulta concluída\",\"dados\":{\"working\":[]}}")
                .andExpect(status().isNoContent());
    }

    @Test void abrirNaoAceitaDados() throws Exception {
        var comando = emExecucao("empresa_abrir");
        enviar(comando, "{\"estado\":\"concluído\",\"mensagem\":\"ok\",\"dados\":{\"x\":1}}")
                .andExpect(status().isBadRequest());
    }
}
