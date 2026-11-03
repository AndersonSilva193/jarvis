package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.agente.Freio;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.ferramentas.*;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class FreioDoAssistenteTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final ClaudeClient claude = mock(ClaudeClient.class);
    final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
            new JarvisProperties.Anthropic("chave", "modelo", 1024, 512), null, null, null, null, null, null, null, null);
    Freio freio(JarvisProperties config, MedidorDeCusto medidor) {
        return new Freio(config, medidor, mock(AvisosProativos.class), new FalasService(), json, new RelogioFalso(), pasta.resolve("freio.json"));
    }
    Assistente assistente(Freio freio, Ferramenta... ferramentas) {
        var a = new Assistente(claude, new Ferramentas(List.of(ferramentas), json),
                new SistemaDoJarvis(new PersonalidadeService(pasta.resolve("ausente.md"), "Anderson"), json, props), json);
        a.configurarFreio(freio); return a;
    }
    @Test void conversaNormalFuncionaPausadaMasTarefaNemChamaModelo() throws Exception {
        var freio = freio(props, mock(MedidorDeCusto.class)); var a = assistente(freio);
        freio.pausar("hud");
        assertEquals("Trabalho autônomo pausado.", a.executarTarefa("edite algo"));
        verifyNoInteractions(claude);
        var resposta = json.createObjectNode().put("stop_reason", "end_turn");
        resposta.putArray("content").addObject().put("type", "text").put("text", "Bom dia, senhor.");
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class))).thenReturn(resposta);
        assertEquals("Bom dia, senhor.", a.responder(42, "bom dia", Canal.TELEGRAM, new OuvinteProgresso() { }).texto());
    }
    @Test void tarefaDespachadaAntesDaPausaNaoRenasceAoConseguirLock() throws Exception {
        var freio = freio(props, mock(MedidorDeCusto.class)); var a = assistente(freio);
        long velha = freio.geracao(); freio.pausar("hud"); freio.retomar("hud");
        assertEquals("Trabalho autônomo pausado.", a.executarTarefa("edite", Canal.TAREFA, new OuvinteProgresso() {
            @Override public long geracaoAutonoma() { return velha; }
        }));
        verifyNoInteractions(claude);
    }
    @Test void tetoNoMeioDoPassoImpedeSegundaFerramentaENovaChamadaAoModelo() throws Exception {
        var config = mock(JarvisProperties.class); when(config.zona()).thenReturn(props.zona()); when(config.tetoAutonomoDeCusto()).thenReturn(1.0);
        var medidor = mock(MedidorDeCusto.class); var freio = freio(config, medidor);
        var primeira = new AtomicInteger(); var segunda = new AtomicInteger();
        Ferramenta clima = ferramenta("clima", () -> { primeira.incrementAndGet(); when(medidor.gastoAutonomoDeHoje()).thenReturn(1.0); });
        Ferramenta noticias = ferramenta("noticias", segunda::incrementAndGet);
        var a = assistente(freio, clima, noticias);
        var resposta = json.createObjectNode().put("stop_reason", "tool_use"); var blocos = resposta.putArray("content");
        for (String nome : List.of("clima", "noticias")) blocos.addObject().put("type", "tool_use").put("id", nome).put("name", nome).set("input", json.createObjectNode());
        when(claude.criarMensagem(any(Origem.class), any(PerfilDeEsforco.class), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class))).thenReturn(resposta);
        assertEquals("Parei, senhor.", a.executarTarefa("consulte o clima e as notícias"));
        assertEquals(1, primeira.get()); assertEquals(0, segunda.get());
        verify(claude, times(1)).criarMensagem(eq(Origem.TAREFA), eq(PerfilDeEsforco.TAREFA), any(ArrayNode.class), any(ArrayNode.class), any(ArrayNode.class));
    }
    Ferramenta ferramenta(String nome, Runnable acao) { return new Ferramenta() {
        @Override public String nome() { return nome; }
        @Override public String descricao() { return nome; }
        @Override public ObjectNode esquemaEntrada() { return json.createObjectNode().put("type", "object"); }
        @Override public String executar(JsonNode entrada) { acao.run(); return "ok"; }
    }; }
}
