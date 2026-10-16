package dev.jarvis.ia;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import dev.jarvis.ferramentas.Ferramenta;
import dev.jarvis.ferramentas.Ferramentas;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ComandoDeRepousoTest {
    @Test void somenteComandoCompleto() {
        for (String texto : List.of("Jarvis, modo repouso!", "fechar HUD", "Jarvis, feche a HUD"))
            assertTrue(ComandoDeRepouso.reconhece(texto));
        for (String texto : List.of("fecha tudo", "fechar video", "como funciona modo repouso?", "nao fechar hud", "fechar hud depois"))
            assertFalse(ComandoDeRepouso.reconhece(texto));
    }
    @Test void encaminhaAoPcSemModeloNosDoisCanais() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        ClaudeClient modelo = mock(ClaudeClient.class);
        Ferramenta pc = mock(Ferramenta.class);
        when(pc.nome()).thenReturn("controlar_pc");
        when(pc.executarDetalhado(any(JsonNode.class), any())).thenReturn(
                dev.jarvis.ferramentas.ResultadoFerramenta.texto("fechamento solicitado"));
        Assistente assistente = new Assistente(modelo, new Ferramentas(List.of(pc), json), mock(SistemaDoJarvis.class), json);
        for (Canal canal : List.of(Canal.HUD, Canal.TELEGRAM))
            assertEquals("fechamento solicitado", assistente.responder(1, "Jarvis, fechar HUD", canal));
        verify(pc, times(2)).executarDetalhado(argThat(entrada -> "modo_repouso".equals(entrada.path("acao").asString())), any());
        verifyNoInteractions(modelo);
    }
}
