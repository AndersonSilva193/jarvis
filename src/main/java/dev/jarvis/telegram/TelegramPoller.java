package dev.jarvis.telegram;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.PedidoDeParada;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * Fica num loop perguntando ao Telegram "tem mensagem nova?" e repassa para o Assistente.
 * Só responde ao usuário configurado em TELEGRAM_USUARIO_PERMITIDO.
 *
 * <p><b>Ler e atender são duas threads.</b> A de leitura só faz {@code getUpdates}, confere quem
 * mandou e enfileira; quem fala com o Assistente é um único trabalhador. Antes era a mesma thread,
 * e um pedido de oito passos deixava o Jarvis surdo: a mensagem "para" ficava no servidor do
 * Telegram até o pedido acabar, quando já não servia para nada. A fila é FIFO e o trabalhador é um
 * só, então a ordem e o "um pedido por vez" continuam valendo.
 */
@Component
public class TelegramPoller implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(TelegramPoller.class);

    /** O recado de que a mensagem chegou, para quem escreveu durante um pedido em andamento. */
    private static final String AVISO_DE_FILA = "Recebi. Respondo assim que terminar o pedido anterior.";
    /** Quando a parada só alcançou a tarefa de tela, não há turno de conversa para responder. */
    private static final String PAREI = "Parei, senhor.";

    /** Uma mensagem já autenticada, esperando a vez. */
    private record Pendente(long chatId, long usuario, long updateId, String texto) { }

    private final TelegramClient telegram;
    private final Assistente assistente;
    private final BriefingService briefing;
    private final TarefasComputerUse tarefas;
    private final long usuarioPermitido;
    private final BlockingQueue<Pendente> fila = new LinkedBlockingQueue<>();
    /** O aviso de espera é por rajada: avisar a cada mensagem seria o Jarvis falando sozinho. */
    private final AtomicBoolean avisouEspera = new AtomicBoolean();
    private volatile boolean rodando;
    private Thread leitor;
    private Thread trabalhador;

    public TelegramPoller(TelegramClient telegram, Assistente assistente, BriefingService briefing,
            TarefasComputerUse tarefas, JarvisProperties props) {
        this.telegram = telegram;
        this.assistente = assistente;
        this.briefing = briefing;
        this.tarefas = tarefas;
        this.usuarioPermitido = props.telegram().usuarioPermitido();
    }

    @Override
    public void start() {
        if (usuarioPermitido == 0) {
            log.warn("TELEGRAM_USUARIO_PERMITIDO não configurado: vou ignorar todas as mensagens e só mostrar "
                    + "nos logs o ID de quem escrever. Copie o seu ID para o .env e reinicie.");
        }
        rodando = true;
        // Threads de plataforma (não-daemon): mantêm o processo vivo mesmo sem servidor web.
        leitor = Thread.ofPlatform().name("telegram-poller").start(this::loop);
        trabalhador = Thread.ofPlatform().name("telegram-trabalho").start(this::trabalhar);
        log.info("Jarvis online, aguardando mensagens no Telegram.");
    }

    @Override
    public void stop() {
        rodando = false;
        if (leitor != null) {
            leitor.interrupt();
        }
        if (trabalhador != null) {
            trabalhador.interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return rodando;
    }

    private void loop() {
        long offset = 0;
        while (rodando) {
            try {
                List<JsonNode> atualizacoes = telegram.buscarAtualizacoes(offset);
                for (JsonNode atualizacao : atualizacoes) {
                    // Avançamos o offset antes de processar: se der erro, a mensagem não volta em loop.
                    offset = Json.numero(atualizacao.path("update_id")) + 1;
                    receber(atualizacao.path("message"), Json.numero(atualizacao.path("update_id")));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Erro ao buscar mensagens do Telegram: {}. Tentando de novo em 5s.", e.getMessage());
                dormir(5_000);
            }
        }
    }

    /**
     * O que roda na thread de leitura: só o que é barato. As três guardas de entrada (nó ausente,
     * usuário permitido, texto vazio) ficam aqui porque a resposta delas é imediata e não depende
     * do Assistente.
     */
    void receber(JsonNode mensagem, long updateId) {
        if (mensagem.isMissingNode()) {
            return;
        }
        long usuario = Json.numero(mensagem.path("from").path("id"));
        long chatId = Json.numero(mensagem.path("chat").path("id"));
        String texto = Json.texto(mensagem.path("text")).trim();

        if (usuario != usuarioPermitido) {
            log.warn("Mensagem ignorada do usuário {} (não é o usuário permitido).", usuario);
            return;
        }
        if (texto.isEmpty()) {
            telegram.enviarMensagem(chatId, "Por enquanto eu só entendo mensagens de texto.");
            return;
        }
        // Tentar a parada antes de olhar se está ocupado evita a corrida entre as duas perguntas:
        // quem devolve "não havia nada a parar" é a própria tentativa, e aí a mensagem segue o
        // caminho normal — ela pode ser a resposta a uma aprovação pendente.
        if (PedidoDeParada.reconhece(texto) && parar(chatId, usuario)) {
            return;
        }
        boolean esperando = assistente.ocupado() || tarefas.emAndamentoDe(usuario) || !fila.isEmpty();
        fila.add(new Pendente(chatId, usuario, updateId, texto));
        if (esperando && avisouEspera.compareAndSet(false, true)) {
            telegram.enviarMensagem(chatId, AVISO_DE_FILA);
        }
    }

    /**
     * Corta o que está em andamento. O turno de conversa marcado responde por si ("Parei, senhor."
     * sai do próprio turno); a tarefa de tela não tem turno nenhum esperando, então a resposta sai
     * daqui.
     */
    private boolean parar(long chatId, long usuario) {
        boolean turno = assistente.interromperPedidoAtual(usuario);
        boolean tela = tarefas.cancelarUltima(usuario);
        if (tela && !turno) {
            telegram.enviarMensagem(chatId, PAREI);
        }
        return turno || tela;
    }

    private void trabalhar() {
        while (rodando) {
            try {
                tratar(fila.take());
                if (fila.isEmpty()) {
                    avisouEspera.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Atende uma mensagem da fila. Roda só no trabalhador, uma por vez. */
    void tratar(Pendente pendente) {
        long chatId = pendente.chatId();
        String texto = pendente.texto();
        try {
            switch (texto) {
                case "/start", "/ajuda" -> telegram.enviarMensagem(chatId, """
                        Olá! Sou o Jarvis. Fale comigo normalmente, por exemplo:
                        • "os sites estão no ar?"
                        • "como está o servidor?"
                        • "vai chover hoje?"
                        • "o que tem de novo em tecnologia?"
                        • "me lembra de enviar o currículo às 15h"
                        • "quais lembretes eu tenho?"

                        Comandos:
                        /bomdia mostra o briefing do dia (clima, lembretes, sistemas e notícias)
                        /limpar apaga o histórico da conversa""");
                case "/bomdia" -> {
                    telegram.mostrarDigitando(chatId);
                    telegram.enviarMensagem(chatId, briefing.gerar().paraTelegram());
                }
                case "/limpar" -> {
                    assistente.limparHistorico(chatId);
                    telegram.enviarMensagem(chatId, "Histórico apagado. Começando do zero.");
                }
                default -> {
                    telegram.mostrarDigitando(chatId);
                    telegram.enviarMensagem(chatId, assistente.responderTelegram(chatId,
                            pendente.usuario(), pendente.updateId(), texto));
                }
            }
        } catch (Exception e) {
            log.error("Erro ao processar mensagem", e);
            telegram.enviarMensagem(chatId, "Tive um problema para processar isso: " + e.getMessage());
        }
    }

    /** Atende a próxima mensagem da fila sem esperar por ela. Existe para o teste. */
    boolean processarProximo() {
        Pendente pendente = fila.poll();
        if (pendente == null) {
            return false;
        }
        tratar(pendente);
        if (fila.isEmpty()) {
            avisouEspera.set(false);
        }
        return true;
    }

    /** Quantas mensagens esperam a vez. Existe para o teste. */
    int tamanhoDaFila() {
        return fila.size();
    }

    private void dormir(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rodando = false;
        }
    }
}
