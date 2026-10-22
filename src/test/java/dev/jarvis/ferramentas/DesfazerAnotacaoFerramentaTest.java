package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.memoria.AnotadorDeMemoria;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O "não anota isso": tirar o que o Jarvis gravou sozinho, sem perguntar — e <b>não</b> tirar o que
 * ele gravou há muito tempo, que é o que o caminho com aprovação existe para cuidar.
 */
class DesfazerAnotacaoFerramentaTest {

    private static final long DONO = 42L;

    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final RelogioFalso relogio = new RelogioFalso();
    private final AtomicBoolean apagouHistorico = new AtomicBoolean();

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

    private OuvinteProgresso contexto() {
        return new OuvinteProgresso() {
            @Override public String canal() { return "telegram"; }
            @Override public long usuarioAutenticado() { return DONO; }
            @Override public String pedidoOriginal() { return "não anota isso"; }
            @Override public String idPedido() { return "t1"; }
            @Override public void marcarMemoriaEsquecida() { apagouHistorico.set(true); }
            @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                apagouHistorico.set(true);
            }
        };
    }

    /** Um anotador que anota de verdade: só assim os ids da janela são os ids do disco. */
    private AnotadorDeMemoria anotadorQueAnotou(MemoriaService memoria, String assunto,
            String conteudo, String evidencia) throws Exception {
        ObjectNode candidato = json.createObjectNode().put("categoria", "perfil")
                .put("assunto", assunto).put("conteudo", conteudo).put("evidencia", evidencia)
                .put("acao", "novo");
        ObjectNode corpo = json.createObjectNode();
        corpo.putArray("candidatos").add(candidato);
        ObjectNode resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text")
                .put("text", json.writeValueAsString(corpo));

        ClaudeClient claude = mock(ClaudeClient.class);
        when(claude.criarMensagem(any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class),
                anyInt(), any(Duration.class))).thenReturn((JsonNode) resposta);
        var anotador = new AnotadorDeMemoria(memoria, claude, json, props(), relogio);
        for (int i = 0; i < 10; i++) anotador.registrarTurno(DONO, DONO, "telegram", evidencia);
        assertTrue(anotador.aguardar(Duration.ofSeconds(5)), "a análise terminou");
        return anotador;
    }

    @Test
    @DisplayName("dentro da janela: tira na hora, marca o assunto e não toca no histórico")
    void tiraSemPerguntar() throws Exception {
        var memoria = memoria();
        var anotador = anotadorQueAnotou(memoria, "editor", "Usa o VS Code", "uso o VS Code");
        assertEquals(1, memoria.listar(DONO, "", 1).total(), "o anotador gravou");
        var ferramenta = new DesfazerAnotacaoFerramenta(memoria, json, anotador);

        String saida = ferramenta.executarDetalhado(json.createObjectNode(), contexto()).texto();

        assertTrue(saida.startsWith("Tirei."), saida);
        assertTrue(saida.contains("editor"), saida);
        assertEquals(0, memoria.listar(DONO, "", 1).total());
        assertTrue(memoria.assuntoRecusado(DONO, "perfil", "", "editor"),
                "sem isso o anotador regravaria o mesmo fato na conversa seguinte");
        assertFalse(apagouHistorico.get(),
                "recusar uma anotação não pode custar a conversa inteira ao dono");
        assertNull(ferramenta.executarDetalhado(json.createObjectNode(), contexto()).confirmacao());
    }

    @Test
    @DisplayName("o segundo pedido não tem mais o que tirar")
    void janelaEConsumidaUmaVezSo() throws Exception {
        var memoria = memoria();
        var anotador = anotadorQueAnotou(memoria, "editor", "Usa o VS Code", "uso o VS Code");
        var ferramenta = new DesfazerAnotacaoFerramenta(memoria, json, anotador);
        ferramenta.executarDetalhado(json.createObjectNode(), contexto());

        String segunda = ferramenta.executarDetalhado(json.createObjectNode(), contexto()).texto();

        assertTrue(segunda.contains("Não há anotação recente"), segunda);
        assertTrue(segunda.contains("esquecer_memoria"), segunda);
    }

    @Test
    @DisplayName("fora da janela de dez minutos não apaga nada e manda pelo caminho com aprovação")
    void foraDaJanelaNaoApagaSozinho() throws Exception {
        var memoria = memoria();
        var anotador = anotadorQueAnotou(memoria, "editor", "Usa o VS Code", "uso o VS Code");
        var ferramenta = new DesfazerAnotacaoFerramenta(memoria, json, anotador);

        relogio.avancar(Duration.ofMinutes(11));
        String saida = ferramenta.executarDetalhado(json.createObjectNode(), contexto()).texto();

        assertTrue(saida.contains("Não há anotação recente"), saida);
        assertTrue(saida.contains("buscar_memoria"), saida);
        assertTrue(saida.contains("Nada foi apagado"), saida);
        assertEquals(1, memoria.listar(DONO, "", 1).total(), "a lembrança antiga fica de pé");
        assertFalse(memoria.assuntoRecusado(DONO, "perfil", "", "editor"));
    }

    @Test
    @DisplayName("anotação que foi correção volta à versão anterior em vez de apagar")
    void anotacaoSobreFatoAntigoVolta() throws Exception {
        var memoria = memoria();
        var antes = memoria.registrar(DONO, "perfil", "", "editor", "Usa o VS Code",
                "declaracao_usuario", "usuario:telegram", "t0");
        var anotador = anotadorQueAnotou(memoria, "editor", "Usa o Cursor", "migrei para o Cursor");
        assertEquals("Usa o Cursor", memoria.ler(DONO, antes.id()).conteudo());
        var ferramenta = new DesfazerAnotacaoFerramenta(memoria, json, anotador);

        String saida = ferramenta.executarDetalhado(json.createObjectNode(), contexto()).texto();

        assertTrue(saida.startsWith("Tirei."), saida);
        assertTrue(saida.contains("Voltei ao que estava antes"), saida);
        assertEquals("Usa o VS Code", memoria.ler(DONO, antes.id()).conteudo());
        assertFalse(memoria.assuntoRecusado(DONO, "perfil", "", "editor"));
    }

    @Test
    @DisplayName("a descrição manda responder só 'Tirei.' e aponta o caminho com aprovação")
    void descricaoEEsquemaFechados() throws Exception {
        var memoria = memoria();
        var ferramenta = new DesfazerAnotacaoFerramenta(memoria, json,
                new AnotadorDeMemoria(memoria, mock(ClaudeClient.class), json, props(), relogio));

        assertTrue(ferramenta.descricao().contains("\"Tirei.\""), ferramenta.descricao());
        assertTrue(ferramenta.descricao().contains("esquecer_memoria"), ferramenta.descricao());
        assertEquals("{\"type\":\"object\",\"properties\":{}}",
                ferramenta.esquemaEntrada().toString(), "não leva argumento nenhum");
        assertEquals("Memória exige pedido autenticado.", ferramenta.executar(json.createObjectNode()));
        assertThrows(IllegalArgumentException.class, () -> ferramenta.executarDetalhado(
                json.createObjectNode(), new OuvinteProgresso() { }));
    }
}
