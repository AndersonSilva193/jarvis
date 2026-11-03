package dev.jarvis.agente;

import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import dev.jarvis.ia.Assistente;
import dev.jarvis.computer.TarefasComputerUse;

/** A borda autentica; o controle não precisa do lock nem de uma chamada ao modelo. */
@Component
public class ControleDoFreio {
    public static final String PAREI = "Parei tudo. Diga 'pode voltar' quando quiser.";
    private final Freio freio;
    private final ObjectProvider<Assistente> assistente;
    private final ObjectProvider<TarefasComputerUse> tarefas;
    public ControleDoFreio(Freio freio, ObjectProvider<Assistente> assistente,
            ObjectProvider<TarefasComputerUse> tarefas) {
        this.freio = freio; this.assistente = assistente; this.tarefas = tarefas;
    }
    public Optional<String> tratar(String texto, long usuario, String canal) {
        if (usuario <= 0) return Optional.empty();
        var comando = ComandoDoFreio.reconhecer(texto);
        if (comando.isEmpty()) return Optional.empty();
        if (comando.get() == ComandoDoFreio.Acao.PAUSAR) {
            assistente.getObject().interromperPedidoAtual(usuario);
            tarefas.getObject().cancelarUltima(usuario);
            freio.pausar(canal);
            return Optional.of(PAREI);
        }
        freio.retomar(canal);
        return Optional.of(freio.podeAgirSozinho("retomada") ? "Voltei ao normal."
                : "Liberei a pausa manual. O trabalho autônomo continua parado pelo teto de hoje, até a virada do dia.");
    }
}
