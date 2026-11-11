package dev.jarvis.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** O aviso do escritório: frase do catálogo, plural certo e as travas contra insistência. */
class AvisarAprovacoesMcpTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final TelegramClient telegram = mock(TelegramClient.class);

    private static JarvisProperties props(String silencio) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("t", 1L), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Proativo(silencio, null, 5, ""));
    }

    private AvisarAprovacoesMcp ferramenta(RelogioFalso relogio, String silencio) {
        var avisos = new AvisosProativos(telegram, props(silencio), relogio);
        return new AvisarAprovacoesMcp(avisos, new FalasService(), json, relogio);
    }

    private ObjectNode argumentos(String tipo, int quantidade) {
        return json.createObjectNode().put("tipo", tipo).put("quantidade", quantidade);
    }

    @Test
    @DisplayName("a mensagem é do catálogo, com o plural certo, e o agente não escreve nada nela")
    void mandaAFraseDoCatalogo() {
        // 14h em São Paulo: fora do silêncio.
        var mcp = ferramenta(new RelogioFalso("2026-11-11T17:00:00Z"), "23:00-07:00");

        ResultadoMcp um = mcp.executar(argumentos("abordagens", 1));
        assertFalse(um.erro());
        verify(telegram).enviarMensagem(1L,
                "O escritório tem 1 abordagem esperando sua aprovação no quadro de tarefas.");

        ResultadoMcp conteudos = mcp.executar(argumentos("conteudos", 3));
        assertFalse(conteudos.erro());
        verify(telegram).enviarMensagem(1L,
                "O escritório tem 3 ideias de conteúdo esperando sua aprovação no quadro de tarefas.");
    }

    @Test
    @DisplayName("o mesmo tipo não apita de novo antes de 15 minutos, nem o mesmo número depois")
    void travasContraInsistencia() {
        var relogio = new RelogioFalso("2026-11-11T17:00:00Z");
        var mcp = ferramenta(relogio, "23:00-07:00");

        mcp.executar(argumentos("abordagens", 3));
        ResultadoMcp cedo = mcp.executar(argumentos("abordagens", 4));
        assertFalse(cedo.erro());
        assertTrue(cedo.texto().startsWith("Não avisei"), cedo.texto());

        relogio.avancar(Duration.ofMinutes(16));
        ResultadoMcp mesmoNumero = mcp.executar(argumentos("abordagens", 3));
        assertTrue(mesmoNumero.texto().startsWith("Não avisei"), mesmoNumero.texto());

        mcp.executar(argumentos("abordagens", 5));
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("no silêncio o aviso fica para depois, e argumento torto é recusado")
    void silencioEArgumentos() {
        // 02h em São Paulo: dentro do silêncio.
        var mcp = ferramenta(new RelogioFalso("2026-11-11T05:00:00Z"), "23:00-07:00");

        ResultadoMcp guardado = mcp.executar(argumentos("propostas", 2));
        assertFalse(guardado.erro());
        assertTrue(guardado.texto().contains("silêncio"), guardado.texto());

        assertTrue(mcp.executar(argumentos("leads", 2)).erro());
        assertTrue(mcp.executar(argumentos("tarefas", 0)).erro());
        assertTrue(mcp.executar(argumentos("tarefas", 100)).erro());
        assertTrue(mcp.executar(json.createObjectNode().put("tipo", "tarefas").put("quantidade", "3"))
                .erro());
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
        assertEquals("avisar_aprovacoes", mcp.nome());
    }
}
