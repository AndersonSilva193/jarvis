package dev.jarvis.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A fila do poller, sem threads e sem rede: {@code receber} é o que a thread de leitura faz, e
 * {@code processarProximo} o que o trabalhador faria.
 */
class TelegramPollerFilaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final long USUARIO = 42;
    private static final String AVISO = "Recebi. Respondo assim que terminar o pedido anterior.";

    private final TelegramClient telegram = mock(TelegramClient.class);
    private final Assistente assistente = mock(Assistente.class);
    private final BriefingService briefing = mock(BriefingService.class);
    private final TarefasComputerUse tarefas = mock(TarefasComputerUse.class);
    private final TelegramPoller poller = new TelegramPoller(telegram, assistente, briefing,
            tarefas, props());

    @Test
    @DisplayName("mensagem durante um pedido entra na fila e só a primeira recebe o aviso")
    void mensagensEnfileiradasAvisamUmaVezSo() {
        when(assistente.ocupado()).thenReturn(true);

        poller.receber(mensagem("quais lembretes eu tenho?"), 1);
        poller.receber(mensagem("e o clima?"), 2);

        assertEquals(2, poller.tamanhoDaFila(), "a ordem é a de chegada, as duas esperam a vez");
        verify(telegram, times(1)).enviarMensagem(USUARIO, AVISO);
    }

    @Test
    @DisplayName("com o Jarvis livre, a mensagem entra na fila sem aviso nenhum")
    void primeiraMensagemNaoAvisa() {
        when(assistente.ocupado()).thenReturn(false);

        poller.receber(mensagem("bom dia"), 1);

        assertEquals(1, poller.tamanhoDaFila());
        verify(telegram, never()).enviarMensagem(anyLong(), eq(AVISO));
    }

    @Test
    @DisplayName("'para' durante um pedido não entra na fila: ele interrompe o turno")
    void paradaNaoEntraNaFila() throws Exception {
        when(assistente.ocupado()).thenReturn(true);
        when(assistente.interromperPedidoAtual(USUARIO)).thenReturn(true);

        poller.receber(mensagem("para"), 1);

        assertEquals(0, poller.tamanhoDaFila());
        verify(assistente).interromperPedidoAtual(USUARIO);
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
        verify(assistente, never()).responderTelegram(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a parada também cancela a tarefa de tela, e aí a resposta sai do poller")
    void paradaCancelaComputerUse() {
        when(assistente.interromperPedidoAtual(USUARIO)).thenReturn(false);
        when(tarefas.cancelarUltima(USUARIO)).thenReturn(true);

        poller.receber(mensagem("cancela"), 1);

        assertEquals(0, poller.tamanhoDaFila());
        verify(tarefas).cancelarUltima(USUARIO);
        verify(telegram).enviarMensagem(USUARIO, "Parei, senhor.");
    }

    @Test
    @DisplayName("'para' sem nada em andamento segue o caminho normal: pode ser resposta a uma aprovação")
    void paradaSemPedidoSegueOCaminhoNormal() throws Exception {
        when(assistente.ocupado()).thenReturn(false);
        when(assistente.interromperPedidoAtual(USUARIO)).thenReturn(false);
        when(tarefas.cancelarUltima(USUARIO)).thenReturn(false);
        when(assistente.responderTelegram(anyLong(), anyLong(), anyLong(), anyString()))
                .thenReturn("Certo, não fiz.");

        poller.receber(mensagem("para"), 7);

        assertEquals(1, poller.tamanhoDaFila());
        assertTrue(poller.processarProximo());
        verify(assistente).responderTelegram(USUARIO, USUARIO, 7, "para");
        verify(telegram).enviarMensagem(USUARIO, "Certo, não fiz.");
    }

    @Test
    @DisplayName("mensagem de outro usuário não chega à fila")
    void outroUsuarioNaoEntra() {
        ObjectNode mensagem = mensagem("oi");
        mensagem.putObject("from").put("id", 7);
        mensagem.putObject("chat").put("id", 7);

        poller.receber(mensagem, 1);

        assertEquals(0, poller.tamanhoDaFila());
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("mensagem sem texto é respondida na própria thread de leitura")
    void mensagemSemTextoNaoEntraNaFila() {
        ObjectNode mensagem = JSON.createObjectNode();
        mensagem.putObject("from").put("id", USUARIO);
        mensagem.putObject("chat").put("id", USUARIO);

        poller.receber(mensagem, 1);

        assertEquals(0, poller.tamanhoDaFila());
        verify(telegram).enviarMensagem(USUARIO, "Por enquanto eu só entendo mensagens de texto.");
    }

    @Test void filaVaziaNaoTemOQueProcessar() {
        assertFalse(poller.processarProximo());
    }

    private static ObjectNode mensagem(String texto) {
        ObjectNode mensagem = JSON.createObjectNode();
        mensagem.putObject("from").put("id", USUARIO);
        mensagem.putObject("chat").put("id", USUARIO);
        mensagem.put("text", texto);
        return mensagem;
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", USUARIO), null,
                null, null, null, null, null, null, null, null);
    }

    /** O nó ausente do getUpdates não pode virar item de fila. */
    @Test void noAusenteEIgnorado() {
        JsonNode ausente = JSON.createObjectNode().path("message");
        poller.receber(ausente, 1);
        assertEquals(0, poller.tamanhoDaFila());
    }
}
