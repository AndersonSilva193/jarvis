package dev.jarvis.escritorio;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.node.ObjectNode;

/**
 * Manda uma escrita de planilha ao agente do PC e diz como foi, sem lançar.
 *
 * <p>Não decide nada sobre tentar de novo: isso é da {@link FilaDoPc}. Aqui só se traduz a
 * resposta do agente num {@link Estado}. Cada envio é um comando novo — o PC guarda o resultado
 * pelo id do comando, e a repetição segura vem do {@code Id} de cada linha, que o agente confere.
 */
@Component
public class EscritaNoPc {

    public enum Estado { GRAVADO, PC_OFFLINE, ARQUIVO_TRAVADO, AGENTE_ANTIGO, ID_INEXISTENTE, RECUSADO, FALHOU }

    public record Resposta(Estado estado, String mensagem) {
        public boolean gravado() {
            return estado == Estado.GRAVADO;
        }
    }

    /** A ida ao PC, separada para o teste trocar por um agente de mentira. */
    @FunctionalInterface
    interface Envio {
        CompletableFuture<ResultadoPc> enviar(ComandoPc comando);
    }

    static final Duration ESPERA = Duration.ofSeconds(30);

    private final Envio envio;
    private final BooleanSupplier online;
    private final Predicate<String> conhece;
    private final Duration espera;

    @Autowired
    public EscritaNoPc(PcService pc) {
        this(pc::enfileirar, pc::online, pc::agenteConhece, ESPERA);
    }

    EscritaNoPc(Envio envio, BooleanSupplier online, Predicate<String> conhece, Duration espera) {
        this.envio = envio;
        this.online = online;
        this.conhece = conhece;
        this.espera = espera;
    }

    public Resposta enviar(PedidoDeEscrita pedido) {
        if (!online.getAsBoolean()) return new Resposta(Estado.PC_OFFLINE, "o PC está desligado");
        if (!conhece.test(pedido.acao()))
            return new Resposta(Estado.AGENTE_ANTIGO, "o agente do PC não conhece " + pedido.acao());
        ObjectNode dados = pedido.dados().deepCopy();
        dados.put("arquivo", pedido.arquivo());
        ComandoPc comando = new ComandoPc(UUID.randomUUID().toString(), pedido.acao(), "",
                Instant.now().toString(), dados);
        ResultadoPc resultado;
        try {
            resultado = envio.enviar(comando).get(espera.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return new Resposta(Estado.FALHOU, "o PC não respondeu a tempo");
        } catch (ExecutionException e) {
            return new Resposta(Estado.FALHOU, "a conexão com o PC falhou");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Resposta(Estado.FALHOU, "interrompido");
        }
        String mensagem = resultado.mensagem() == null ? "" : resultado.mensagem();
        if (resultado.ok()) return new Resposta(Estado.GRAVADO, mensagem);
        if (mensagem.startsWith("arquivo_travado")) return new Resposta(Estado.ARQUIVO_TRAVADO, mensagem);
        if (mensagem.startsWith("id_inexistente")) return new Resposta(Estado.ID_INEXISTENTE, mensagem);
        // Recusa do agente (arquivo fora da lista, planilha grande demais, link): tentar de novo não muda.
        if (mensagem.startsWith("nao_permitido")) return new Resposta(Estado.RECUSADO, mensagem);
        return new Resposta(Estado.FALHOU, mensagem);
    }
}
