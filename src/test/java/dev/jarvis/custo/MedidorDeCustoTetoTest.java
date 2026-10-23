package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * O teto: um aviso em 80%, outro em 100%, uma vez cada por dia — e nunca um bloqueio.
 *
 * Preço de exemplo: um milhão de tokens de entrada custa US$ 1,00, então o teto de US$ 2,00 é
 * cruzado em dois milhões e os 80% em um milhão e seiscentos mil.
 */
class MedidorDeCustoTetoTest {

    private static final long CHAT = 42;
    private static final String PRECOS = "modelo-a=1/5/1.25/0.1";

    @TempDir
    Path pasta;

    private final TelegramClient telegram = mock(TelegramClient.class);
    private final RelogioFalso relogio = new RelogioFalso("2026-10-22T15:00:00Z");

    private MedidorDeCusto medidor(String teto, long usuario) {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", usuario), null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Custo(null, PRECOS, "", teto));
        var novo = new MedidorDeCusto(props, telegram, JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);
        return novo;
    }

    private final java.util.List<MedidorDeCusto> criados = new java.util.ArrayList<>();

    /** Fecha o executor de gravacao: sem isso o @TempDir nao consegue ser apagado no Windows. */
    @org.junit.jupiter.api.AfterEach
    void fechar() {
        criados.forEach(MedidorDeCusto::fechar);
    }

    /** Gasta "quantos" dólares, em pedaços de um milhão de tokens de entrada por dólar. */
    private static void gastar(MedidorDeCusto medidor, double dolares) {
        medidor.registrar(Origem.CONVERSA, "modelo-a", (long) (dolares * 1_000_000), 0, 0, 0, 0);
        medidor.aguardar();
    }

    @Test
    @DisplayName("abaixo dos 80% o Jarvis não incomoda ninguém")
    void abaixoDosOitentaNaoAvisa() {
        var medidor = medidor("2.00", CHAT);
        gastar(medidor, 1.50);   // 75%
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("o aviso de 80% sai uma vez, não a cada chamada depois dele")
    void avisoDeOitentaSaiUmaVez() {
        var medidor = medidor("2.00", CHAT);
        gastar(medidor, 1.70);   // 85%
        // O aviso diz a porcentagem de verdade, não o limite que ele cruzou.
        verify(telegram, times(1)).enviarMensagem(eq(CHAT), contains("85% do teto"));

        gastar(medidor, 0.10);
        gastar(medidor, 0.10);   // 95%, ainda abaixo do teto
        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("passar do teto manda o segundo aviso, e só o segundo")
    void avisoDeCemSaiUmaVez() {
        var medidor = medidor("2.00", CHAT);
        gastar(medidor, 1.70);   // 85% -> primeiro aviso
        gastar(medidor, 0.50);   // 110% -> segundo aviso
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());
        verify(telegram, times(1)).enviarMensagem(eq(CHAT), contains("passei do teto"));

        gastar(medidor, 5.00);
        gastar(medidor, 5.00);
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("o teto avisa, nunca bloqueia: os totais seguem crescendo acima de 100%")
    void tetoNaoBloqueia() {
        var medidor = medidor("2.00", CHAT);
        gastar(medidor, 3.00);
        gastar(medidor, 4.00);
        assertEquals(7.0, medidor.hoje().totalUsd(), 1e-9);
        assertTrue(medidor.hoje().percentualDoTeto() >= 300);
    }

    @Test
    @DisplayName("um restart no meio do dia não repete os avisos que o dono já leu")
    void restartNaoRepeteOAviso() {
        var antes = medidor("2.00", CHAT);
        gastar(antes, 1.70);
        gastar(antes, 0.50);
        antes.aguardar();
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());

        var depois = medidor("2.00", CHAT);
        gastar(depois, 1.00);
        assertTrue(depois.hoje().totalUsd() > 3.0, "o gasto veio do disco");
        verify(telegram, times(2)).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("teto trocado no meio do dia reabre os avisos: o dono quer saber do novo")
    void tetoTrocadoReabreOsAvisos() {
        var antes = medidor("10.00", CHAT);
        gastar(antes, 9.00);     // 90% do teto antigo -> um aviso
        antes.aguardar();
        verify(telegram, times(1)).enviarMensagem(anyLong(), anyString());

        var depois = medidor("2.00", CHAT);
        gastar(depois, 0.01);    // já estava muito acima do teto novo
        verify(telegram, times(1)).enviarMensagem(eq(CHAT), contains("passei do teto"));
    }

    @Test
    @DisplayName("sem teto configurado não há aviso nenhum")
    void semTetoNaoAvisa() {
        var medidor = medidor("", CHAT);
        gastar(medidor, 100.00);
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
        assertEquals(100.0, medidor.hoje().totalUsd(), 1e-9);
    }

    @Test
    @DisplayName("sem dono configurado ninguém é avisado, e nada quebra")
    void semDonoNaoAvisa() {
        var medidor = medidor("2.00", 0);
        gastar(medidor, 5.00);
        verify(telegram, never()).enviarMensagem(anyLong(), anyString());
    }

    @Test
    @DisplayName("o envio lento do aviso não atrasa a resposta ao usuário")
    void envioDoAvisoEhAssincrono() throws InterruptedException {
        var comecou = new CountDownLatch(1);
        var liberar = new CountDownLatch(1);
        doAnswer(invocacao -> {
            comecou.countDown();
            liberar.await(5, TimeUnit.SECONDS);
            return null;
        }).when(telegram).enviarMensagem(eq(CHAT), anyString());
        var medidor = medidor("2.00", CHAT);

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                    medidor.registrar(Origem.CONVERSA, "modelo-a", 1_700_000, 0, 0, 0, 0));
            assertTrue(comecou.await(5, TimeUnit.SECONDS));
        } finally {
            liberar.countDown();
        }
        medidor.aguardar();
        verify(telegram, times(1)).enviarMensagem(eq(CHAT), anyString());
    }
}
