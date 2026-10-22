package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.ClaudeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O anotador grava sozinho, então o que estes testes guardam é o <b>que ele recusa</b>: evidência
 * que o usuário não escreveu, segredo, e resposta torta do modelo.
 */
class AnotadorDeMemoriaTest {
    private static final long DONO = 42L;
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-22T14:00:00Z"), ZoneId.of("UTC"));

    private MemoriaService memoria() {
        return new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json,
                relogio, ZoneId.of("America/Sao_Paulo"));
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Memoria("", "", true, 5));
    }

    /** Monta a resposta do modelo no formato combinado. */
    private JsonNode candidatos(ObjectNode... itens) {
        ObjectNode corpo = json.createObjectNode();
        ArrayNode lista = corpo.putArray("candidatos");
        for (ObjectNode item : itens) lista.add(item);
        return texto(json.writeValueAsString(corpo));
    }

    private JsonNode texto(String conteudo) {
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", conteudo);
        return resposta;
    }

    private ObjectNode candidato(String categoria, String assunto, String conteudo, String evidencia) {
        return json.createObjectNode().put("categoria", categoria).put("assunto", assunto)
                .put("conteudo", conteudo).put("evidencia", evidencia).put("acao", "novo");
    }

    private AnotadorDeMemoria anotador(MemoriaService memoria, JsonNode resposta) throws Exception {
        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                anyInt(), any(Duration.class))).thenReturn(resposta);
        return new AnotadorDeMemoria(memoria, claude, json, props(), relogio);
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

    @Test void evidenciaForaDaMensagemNaoGrava() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "cidade", "Mora em São Paulo", "eu moro em São Paulo")));
        conversar(anotador, "trabalho com Java");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertTrue(memoria.assuntos(DONO).isEmpty(), "evidência que o usuário não escreveu não grava");
        assertEquals("", anotador.drenarAviso(DONO));
    }

    @Test void segredoERecusado() throws Exception {
        var memoria = memoria();
        // A evidência passa (é literal), e quem recusa é o filtro de segredo do MemoriaService.
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "acesso", "A senha do banco dele é hunter2", "a senha do banco é hunter2")));
        conversar(anotador, "a senha do banco é hunter2");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertTrue(memoria.assuntos(DONO).isEmpty());
        assertEquals("", anotador.drenarAviso(DONO));
    }

    @Test void duplicadoViraAtualizacaoComHistorico() throws Exception {
        var memoria = memoria();
        var antes = memoria.registrar(DONO, "perfil", "", "cidade", "Mora em Curitiba",
                "declaracao_usuario", "usuario:telegram", "t1");
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "cidade", "Mudou para Florianópolis", "me mudei para Florianópolis")));
        conversar(anotador, "me mudei para Florianópolis no mês passado");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        var depois = memoria.ler(DONO, antes.id());
        assertNotNull(depois);
        assertEquals(2, depois.revisao(), "mesmo assunto não duplica, atualiza");
        assertEquals("Mudou para Florianópolis", depois.conteudo());
        assertEquals("anotador:telegram", depois.origem());
        assertEquals(1, memoria.assuntos(DONO).size());
        assertTrue(Files.exists(pasta.resolve("memoria").resolve("usuarios").resolve(String.valueOf(DONO))
                .resolve(".historico").resolve(antes.id()).resolve("1.md")), "a revisão anterior fica no histórico");
    }

    @Test void acaoErradaDoModeloNaoDuplica() throws Exception {
        var memoria = memoria();
        memoria.registrar(DONO, "perfil", "", "cidade", "Mora em Curitiba", "declaracao_usuario",
                "usuario:telegram", "t1");
        // O modelo diz "novo" para um assunto que já existe: quem casa o assunto é o servidor.
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "Cidade", "Mudou para Florianópolis", "me mudei para Florianópolis")));
        conversar(anotador, "me mudei para Florianópolis");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertEquals(1, memoria.assuntos(DONO).size());
    }

    @Test void respostaInvalidaNaoGravaNada() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, texto("Claro, senhor. Não encontrei nada digno de nota."));
        conversar(anotador, "trabalho com Java e moro em Curitiba");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertTrue(memoria.assuntos(DONO).isEmpty());
        assertEquals("", anotador.drenarAviso(DONO));
    }

    @Test void conteudoIgualNaoGeraAviso() throws Exception {
        var memoria = memoria();
        memoria.registrar(DONO, "perfil", "", "cidade", "Mora em Curitiba", "declaracao_usuario",
                "usuario:telegram", "t1");
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "cidade", "Mora em Curitiba", "moro em Curitiba")));
        conversar(anotador, "moro em Curitiba desde 2019");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertEquals("", anotador.drenarAviso(DONO), "nada mudou, nada a avisar");
    }

    @Test void avisoSaiUmaVezSo() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "editor", "Usa o VS Code no dia a dia", "uso o VS Code"),
                candidato("preferencias", "cafe", "Toma café sem açúcar", "café sem açúcar")));
        conversar(anotador, "uso o VS Code e tomo café sem açúcar");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        assertEquals(2, memoria.assuntos(DONO).size());
        String aviso = anotador.drenarAviso(DONO);
        assertTrue(aviso.startsWith("Anotei 2 coisas: "), aviso);
        assertTrue(aviso.contains("Usa o VS Code no dia a dia"), aviso);
        assertTrue(aviso.endsWith("."), aviso);
        assertEquals("", anotador.drenarAviso(DONO), "o aviso sai na resposta seguinte e só nela");
    }

    @Test void umItemUsaOSingular() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "editor", "Usa o VS Code no dia a dia", "uso o VS Code")));
        conversar(anotador, "uso o VS Code");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertEquals("Anotei: Usa o VS Code no dia a dia.", anotador.drenarAviso(DONO));
    }

    @Test void grupoEBriefingNaoEntramNoTrecho() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "editor", "Usa o VS Code", "uso o VS Code")));
        // usuario = 0 é o grupo do Telegram; "briefing" não tem ninguém para avisar depois.
        for (int i = 0; i < 12; i++) anotador.registrarTurno(DONO, 0, "telegram", "uso o VS Code");
        for (int i = 0; i < 12; i++) anotador.registrarTurno(DONO, DONO, "briefing", "uso o VS Code");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertTrue(memoria.assuntos(DONO).isEmpty());
    }

    @Test void desligadoNaoAnotaNada() throws Exception {
        var memoria = memoria();
        ClaudeClient claude = mock(ClaudeClient.class);
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Memoria("", "", false, 5));
        var anotador = new AnotadorDeMemoria(memoria, claude, json, props, relogio);
        conversar(anotador, "moro em Curitiba");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        assertTrue(memoria.assuntos(DONO).isEmpty());
        org.mockito.Mockito.verifyNoInteractions(claude);
    }

    @Test void assuntoRecusadoPeloDonoNaoVoltaAserAnotado() throws Exception {
        var memoria = memoria();
        var antes = memoria.anotar(DONO, "perfil", "", "editor", "Usa o VS Code",
                "anotador:telegram", "a0");
        memoria.desfazerUltimaEscrita(DONO, antes.id());
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "editor", "Usa o VS Code", "uso o VS Code")));

        conversar(anotador, "uso o VS Code");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        assertTrue(memoria.assuntos(DONO).isEmpty(),
                "anotar de novo o que o dono mandou tirar seria desfazer o desfazer");
        assertEquals("", anotador.drenarAviso(DONO));
    }

    @Test void oQueFoiAnotadoFicaAlcancavelParaODesfazer() throws Exception {
        var memoria = memoria();
        var anotador = anotador(memoria, candidatos(
                candidato("perfil", "editor", "Usa o VS Code", "uso o VS Code")));
        conversar(anotador, "uso o VS Code");
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");

        // O aviso é consumido pela resposta ao usuário; o desfazer não pode depender dele.
        assertEquals("Anotei: Usa o VS Code.", anotador.drenarAviso(DONO));

        var desfazivel = anotador.consumirDesfazivel(DONO);
        assertTrue(desfazivel.isPresent(), "ainda dá para dizer 'não anota isso'");
        assertEquals(memoria.assuntos(DONO).getFirst().id(), desfazivel.get().ids().getFirst());
        assertTrue(anotador.consumirDesfazivel(DONO).isEmpty(), "uma vez só");
        assertTrue(anotador.consumirDesfazivel(0).isEmpty(), "sem usuário não há o que desfazer");
    }

    @Test void listaDeAssuntosNaoLevaConteudoAoPrompt() throws Exception {
        var memoria = memoria();
        memoria.registrar(DONO, "perfil", "", "cidade", "Mora em Curitiba", "declaracao_usuario",
                "usuario:telegram", "t1");
        List<MemoriaService.Achado> fichas = memoria.assuntos(DONO);
        assertEquals(1, fichas.size());
        assertEquals("cidade", fichas.getFirst().assunto());
        assertEquals("perfil", fichas.getFirst().tipo());
        assertEquals("", fichas.getFirst().trecho(), "o conteúdo não vai ao prompt do anotador");
    }
}
