package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A fila de comandos do PC: entrega, resposta, validade e o sinal de vida do agente. */
class PcServiceTest {

    private static final Duration CURTA = Duration.ofMillis(50);

    private final RelogioFalso relogio = new RelogioFalso();
    private final PcService pc = new PcService(relogio);

    private ComandoPc comando(String acao) {
        return ComandoPc.nova(acao, "", relogio.instant());
    }

    @Test
    @DisplayName("entrega o comando ao agente e devolve o resultado a quem espera")
    void entregaEResponde() throws Exception {
        ComandoPc enviado = comando("bloquear_tela");
        CompletableFuture<ResultadoPc> futuro = pc.enfileirar(enviado);

        Optional<ComandoPc> recebido = pc.proximoComando(CURTA, null);
        assertTrue(recebido.isPresent(), "o agente tinha que receber o comando");
        assertEquals(enviado.id(), recebido.get().id());
        assertFalse(futuro.isDone(), "nada responde sozinho antes do agente postar");

        assertTrue(pc.concluir(new ResultadoPc(enviado.id(), true, "tela trancada", null, null)));
        assertEquals("tela trancada", futuro.get(1, TimeUnit.SECONDS).mensagem());
        assertEquals(0, pc.naFila(), "comando respondido sai de circulação");
    }

    @Test
    @DisplayName("sem comando na fila, a espera do agente termina vazia")
    void esperaVazia() {
        assertTimeoutPreemptively(Duration.ofSeconds(2),
                () -> assertTrue(pc.proximoComando(CURTA, null).isEmpty()));
    }

    @Test
    @DisplayName("comando parado 60 s vence e libera quem espera")
    void comandoVence() throws Exception {
        CompletableFuture<ResultadoPc> futuro = pc.enfileirar(comando("desligar"));

        relogio.avancar(Duration.ofSeconds(61));
        pc.expirarPendentes();

        ResultadoPc resultado = futuro.get(1, TimeUnit.SECONDS);
        assertFalse(resultado.ok());
        assertTrue(resultado.mensagem().contains("não respondeu"), resultado.mensagem());
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("comando vencido não é entregue ao agente")
    void vencidoNaoEntrega() {
        pc.enfileirar(comando("volume"));
        relogio.avancar(Duration.ofSeconds(61));

        assertTrue(pc.proximoComando(CURTA, null).isEmpty(), "comando velho não roda atrasado");
        assertEquals(0, pc.naFila());
    }

    @Test
    @DisplayName("resultado de id desconhecido, vencido ou repetido é recusado")
    void resultadoRecusado() {
        assertFalse(pc.concluir(new ResultadoPc("nao-existe", true, "", null, null)),
                "id desconhecido");

        ComandoPc enviado = comando("bloquear_tela");
        pc.enfileirar(enviado);
        assertTrue(pc.concluir(new ResultadoPc(enviado.id(), true, "ok", null, null)));
        assertFalse(pc.concluir(new ResultadoPc(enviado.id(), true, "ok de novo", null, null)),
                "retry de rede do agente não pode ser aceito duas vezes");

        ComandoPc expirado = comando("volume");
        pc.enfileirar(expirado);
        relogio.avancar(Duration.ofSeconds(61));
        pc.expirarPendentes();
        assertFalse(pc.concluir(new ResultadoPc(expirado.id(), true, "atrasado", null, null)),
                "resultado que chega depois da validade não vale");
    }

    @Test
    @DisplayName("o PC fica offline depois de 90 s sem contato")
    void offlineSemContato() {
        assertFalse(pc.online(), "antes do primeiro contato o agente não está rodando");
        assertTrue(pc.desdeOUltimoContato().isEmpty());

        pc.proximoComando(CURTA, null);
        assertTrue(pc.online());

        relogio.avancar(Duration.ofSeconds(91));
        assertFalse(pc.online());
        assertEquals(91, pc.desdeOUltimoContato().orElseThrow().toSeconds());
    }

    @Test
    @DisplayName("o POST de resultado também conta como sinal de vida")
    void resultadoRenovaContato() {
        ComandoPc enviado = comando("bloquear_tela");
        pc.enfileirar(enviado);
        pc.concluir(new ResultadoPc(enviado.id(), true, "ok", null, null));

        assertTrue(pc.online());
    }

    @Test
    @DisplayName("a lista de apps é limpa de nome inválido, deduplicada e limitada")
    void listaDeApps() {
        pc.proximoComando(CURTA, "Chrome, vs code, ok;rm -rf, chrome, <script>, spotify");
        assertEquals(List.of("chrome", "spotify"), pc.appsConhecidos(),
                "nome com espaço, ponto-e-vírgula ou sinal de maior não entra no prompt do modelo");

        // Cabeçalho ausente é ausência de informação: um agente antigo não apaga o enum.
        pc.proximoComando(CURTA, null);
        assertEquals(List.of("chrome", "spotify"), pc.appsConhecidos());

        // Presente e vazio é informação explícita de que não há app liberado.
        pc.proximoComando(CURTA, "");
        assertTrue(pc.appsConhecidos().isEmpty());
    }

    @Test
    @DisplayName("a lista de apps tem teto, para o cabeçalho não engordar todo pedido em tokens")
    void listaDeAppsTemTeto() {
        StringBuilder muitos = new StringBuilder("app0");
        for (int i = 1; i < 50; i++) {
            muitos.append(",app").append(i);
        }
        pc.proximoComando(CURTA, muitos.toString());

        assertEquals(30, pc.appsConhecidos().size());
    }

    @Test
    @DisplayName("nenhum método segura lock durante a espera do long poll")
    void esperaNaoTravaAFila() {
        // É a invariante que mantém o bot vivo: a ferramenta enfileira dentro do lock do
        // Assistente, então se o long poll do agente segurasse um monitor da classe, todo pedido
        // ficaria esperando até 25 s. Aqui o poll fica 2 s parado e o enfileirar tem de passar.
        Thread agente = new Thread(() -> pc.proximoComando(Duration.ofSeconds(2), null));
        agente.setDaemon(true);
        agente.start();

        assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
            while (pc.desdeOUltimoContato().isEmpty()) {
                Thread.sleep(5);   // espera o agente entrar no poll
            }
            Instant antes = Instant.now();
            pc.enfileirar(comando("volume"));
            assertTrue(Duration.between(antes, Instant.now()).toMillis() < 200,
                    "enfileirar não pode esperar o long poll terminar");
        });
    }
}
