package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.ia.ClaudeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O gancho autônomo mais sutil: o que o anotador grava por conta própria entra no diário, um por
 * fato, com origem {@code anotador} — que não é canal nenhum.
 */
class AnotadorDiarioTest {

    private static final long DONO = 42L;

    @TempDir
    Path pasta;

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-22T14:00:00Z"), ZoneId.of("UTC"));
    private DiarioDeAcoes diario;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Memoria("", "", true, 5), null, null, null,
                new JarvisProperties.Diario(null));
    }

    private MemoriaService memoria() {
        return new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json,
                relogio, ZoneId.of("America/Sao_Paulo"));
    }

    private AnotadorDeMemoria anotador(MemoriaService memoria, ObjectNode... candidatos)
            throws Exception {
        ObjectNode corpo = json.createObjectNode();
        ArrayNode lista = corpo.putArray("candidatos");
        for (ObjectNode item : candidatos) lista.add(item);
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text")
                .put("text", json.writeValueAsString(corpo));

        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(Origem.class), any(ArrayNode.class), any(ArrayNode.class),
                any(ArrayNode.class), anyInt(), any(Duration.class)))
                .thenReturn((JsonNode) resposta);

        diario = new DiarioDeAcoes(props(), json, relogio, pasta.resolve("diario"));
        return new AnotadorDeMemoria(memoria, claude, json, props(), relogio, diario);
    }

    private ObjectNode candidato(String assunto, String conteudo, String evidencia) {
        return json.createObjectNode().put("categoria", "perfil").put("assunto", assunto)
                .put("conteudo", conteudo).put("evidencia", evidencia).put("acao", "novo");
    }

    /** Dez turnos fecham o trecho e disparam a análise sem esperar a conversa parar. */
    private void conversar(AnotadorDeMemoria anotador, String... mensagens) {
        for (String mensagem : mensagens) {
            anotador.registrarTurno(DONO, DONO, "telegram", mensagem);
        }
        for (int i = mensagens.length; i < 10; i++) {
            anotador.registrarTurno(DONO, DONO, "telegram", "mensagem de enchimento " + i);
        }
    }

    @Test
    @DisplayName("o que o anotador grava entra no diário, um por fato, com origem anotador")
    void oQueOAnotadorGravaEntraNoDiario() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria,
                candidato("linguagem", "Trabalha com Java", "trabalho com Java"),
                candidato("editor", "Usa o VS Code", "uso o VS Code"));

        conversar(anotador, "trabalho com Java", "uso o VS Code");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        assertEquals(2, memoria.assuntos(DONO).size(), "os dois fatos foram gravados");

        diario.aguardar();
        var acoes = diario.doDia(LocalDate.parse("2026-10-22")).acoes();
        assertEquals(2, acoes.size(), "uma linha por fato anotado, não uma por lote");
        assertEquals(TipoDeAcao.ANOTAR_MEMORIA, acoes.getFirst().tipo());
        assertEquals(OrigemDaAcao.ANOTADOR, acoes.getFirst().origem(),
                "o anotador não age por um canal: ele age por conta própria");
        assertTrue(acoes.getFirst().descricao().contains("linguagem"),
                "a descrição traz o assunto que o MemoriaService gravou: "
                        + acoes.getFirst().descricao());
    }

    @Test
    @DisplayName("candidato recusado não entra no diário: nada foi gravado")
    void candidatoRecusadoNaoEntraNoDiario() throws Exception {
        var memoria = memoria();
        // Evidência que o usuário não escreveu: o MemoriaService recusa antes de gravar.
        var anotador = anotador(memoria,
                candidato("cidade", "Mora em São Paulo", "eu moro em São Paulo"));

        conversar(anotador, "trabalho com Java");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        assertTrue(memoria.assuntos(DONO).isEmpty(), "nada foi gravado");
        diario.aguardar();
        assertTrue(diario.doDia(LocalDate.parse("2026-10-22")).acoes().isEmpty(),
                "o diário registra o que foi feito, não o que o modelo propôs");
    }
}
