package dev.jarvis.conversa;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;

/** Aceitação rápida na HUD; o trabalho ocorre numa virtual thread, sem chamar o Telegram. */
@Service
public class ConversaService {
    private final Assistente assistente;
    private final RegistroConversas registro;
    private final long chatId;
    private final ExecutorService tarefas = Executors.newVirtualThreadPerTaskExecutor();

    public ConversaService(Assistente assistente, RegistroConversas registro, JarvisProperties props) {
        this.assistente = assistente;
        this.registro = registro;
        this.chatId = props.telegram().usuarioPermitido();
    }

    public String enviar(String texto) {
        var pedido = registro.reservar(chatId, texto);
        try {
            tarefas.submit(() -> {
                try {
                    assistente.responder(chatId, texto, Canal.HUD, new OuvinteProgresso() {
                        @Override public String idPedido() { return pedido.id(); }
                        @Override public void iniciado() { registro.processando(chatId, pedido.id()); }
                        @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                            registro.progresso(chatId, new RegistroConversas.Progresso(
                                    pedido.id(), "hud", nome, descricao, passo, total));
                        }
                    });
                } catch (Exception e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    registro.falhar(chatId, pedido.id());
                }
            });
        } catch (RuntimeException e) {
            registro.falhar(chatId, pedido.id());
            throw e;
        }
        return pedido.id();
    }

    public RegistroConversas.Historico historico() { return registro.historico(chatId); }
    public void limpar() {
        registro.reservarLimpeza(chatId);
        assistente.limparHistorico(chatId);
    }
    @PreDestroy public void fechar() { tarefas.shutdownNow(); }
}
