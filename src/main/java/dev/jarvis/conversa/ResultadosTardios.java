package dev.jarvis.conversa;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.stereotype.Component;

/**
 * O que terminou fora de um turno e o dono já recebeu, esperando para entrar no histórico.
 *
 * <p>A tarefa de computer use roda numa thread própria, fora do lock do {@code Assistente}, e o
 * aviso final ia direto ao Telegram. O dono lia o resultado, mas o modelo não: na pergunta
 * seguinte ("qual o telefone da segunda?") o histórico não tinha nada daquilo, e o
 * {@code status_tarefa_no_pc} só devolvia o mesmo texto do aviso. Esta fila fecha o buraco.
 *
 * <p>Quem termina chama {@link #registrar} e segue — nada de segurar o lock da conversa no fim
 * de uma tarefa. Quem drena é o {@code Assistente.responder}, já dentro do lock, no começo do
 * próximo pedido do mesmo chat: cada item vira um turno {@code user}/{@code assistant} antes do
 * pedido novo, que é a ordem em que as coisas aconteceram. É o idioma do
 * {@code AnotadorDeMemoria.drenarAviso}, no sentido contrário.
 *
 * <p>Em memória, de propósito: o item vive segundos ou minutos, até a próxima mensagem, e depois
 * passa a morar no histórico em disco junto com o turno que o drenou.
 */
@Component
public class ResultadosTardios {

    /** Teto por chat. Uma fila que ninguém drena não pode crescer para sempre. */
    static final int MAX_POR_CHAT = 5;

    /**
     * @param origem o que produziu o resultado, numa frase montada em Java ("a tarefa no PC X")
     * @param texto  exatamente o que o dono recebeu
     */
    public record Item(String origem, String texto) { }

    private final Map<Long, ConcurrentLinkedQueue<Item>> pendentes = new ConcurrentHashMap<>();

    public void registrar(long chatId, String origem, String texto) {
        if (chatId <= 0 || texto == null || texto.isBlank()) return;
        var fila = pendentes.computeIfAbsent(chatId, chave -> new ConcurrentLinkedQueue<>());
        fila.add(new Item(origem == null ? "" : origem, texto));
        while (fila.size() > MAX_POR_CHAT) fila.poll();
    }

    /** Devolve e esvazia o que estava esperando, do mais velho para o mais novo. */
    public List<Item> drenar(long chatId) {
        var fila = pendentes.remove(chatId);
        if (fila == null) return List.of();
        List<Item> itens = new ArrayList<>();
        for (Item item; (item = fila.poll()) != null; ) itens.add(item);
        return itens;
    }
}
