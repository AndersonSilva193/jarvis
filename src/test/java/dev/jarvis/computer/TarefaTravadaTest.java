package dev.jarvis.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A tarefa que anda em círculos para antes dos tetos. Ciclos e ações ficam folgados de propósito:
 * se o teste passar, foi o {@link DetectorDeTravamento} que encerrou, e não outro limite.
 */
class TarefaTravadaTest {
    private static final byte[] MESMA_TELA = "janela parada".getBytes(StandardCharsets.UTF_8);

    private final JsonMapper json = JsonMapper.builder().build();
    private final OperacoesPcService e2 = mock(OperacoesPcService.class);
    private final SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RegistroConversas conversas = mock(RegistroConversas.class);
    private final ApplicationEventPublisher eventos = mock(ApplicationEventPublisher.class);
    private final JarvisProperties props = new JarvisProperties(null, null, null, null, null, null,
            null, null, null, null, null, null, null,
            new JarvisProperties.ComputerUse(true, "anthropic", "modelo", "", "", 20, 50, 60),
            null, null);

    /** Provedor preso: pede sempre o mesmo clique e nunca diz que terminou. */
    private final ProvedorComputerUse provedorPreso = new ProvedorComputerUse() {
        @Override public String nome() { return "dublê"; }
        @Override public String modelo() { return "modelo-de-teste"; }
        @Override public Passo iniciar(String objetivo, byte[] capturaInicial, ArrayNode sistema,
                Duration restante) {
            return mesmoClique();
        }
        @Override public Passo continuar(List<Resultado> resultados, Duration restante) {
            return mesmoClique();
        }
        private Passo mesmoClique() {
            ObjectNode entrada = json.createObjectNode();
            entrada.putArray("coordinate").add(10).add(20);
            return new Passo(List.of(new Chamada("call_1", "left_click", entrada)), "", 1, 1);
        }
    };

    @Test void cliqueRepetidoSemMudancaEncerraATarefaComMotivoLegivel() {
        when(e2.online()).thenReturn(true);
        when(sistema.blocos(Canal.TAREFA)).thenReturn(json.createArrayNode());
        when(e2.enfileirar(any(), any(), any(), any(), anyLong())).thenAnswer(invocacao -> {
            ComandoPc comando = invocacao.getArgument(0);
            return CompletableFuture.completedFuture(
                    new ResultadoPc(comando.id(), true, "", MESMA_TELA, "image/png"));
        });
        var tarefas = new TarefasComputerUse(e2, sistema, props, json, null, telegram, conversas,
                eventos, () -> provedorPreso);

        assertTrue(tarefas.iniciar("clicar no botao de entrar", "telegram", 42)
                .contains("iniciada"));

        ArgumentCaptor<String> aviso = ArgumentCaptor.forClass(String.class);
        verify(telegram, timeout(10_000)).enviarMensagem(eq(42L), aviso.capture());
        assertTrue(aviso.getValue().contains("parei: repeti left_click 3 vezes sem mudança na tela"),
                "o usuario recebe o aviso final de sempre, com o motivo do travamento: "
                        + aviso.getValue());

        var retrato = tarefas.ultima(42);
        assertEquals("falhou", retrato.estado());
        assertEquals("parei: repeti left_click 3 vezes sem mudança na tela", retrato.motivo());
        assertEquals(3, retrato.ciclos(), "parou no terceiro clique, longe do teto de 20 ciclos");
    }
}
