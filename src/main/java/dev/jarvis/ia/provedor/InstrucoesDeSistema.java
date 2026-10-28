package dev.jarvis.ia.provedor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * O prompt de sistema de um pedido, montado uma vez e repetido em todas as idas e vindas.
 *
 * <p>Guarda também a forma que cada provedor fez dele, e é isso que leva o "montado uma vez" até o
 * fio: o mesmo objeto daqui vira o mesmo objeto lá em todos os passos do turno. Remontar o system no
 * meio do turno invalida os blocos de pensamento que o modelo já devolveu, e é exatamente o que a
 * identidade prova nos testes — igualdade passaria com o bug, porque no mesmo minuto o texto sairia
 * igual.
 */
public final class InstrucoesDeSistema {

    private final List<BlocoDeSistema> blocos;
    private final Map<String, Object> renderizadas = new ConcurrentHashMap<>();

    public InstrucoesDeSistema(List<BlocoDeSistema> blocos) {
        this.blocos = List.copyOf(blocos);
    }

    public static InstrucoesDeSistema de(BlocoDeSistema... blocos) {
        return new InstrucoesDeSistema(List.of(blocos));
    }

    public List<BlocoDeSistema> blocos() {
        return blocos;
    }

    /** A forma do provedor, feita na primeira vez que ele pede e devolvida igual depois. */
    @SuppressWarnings("unchecked")
    public <T> T renderizada(String provedor, Function<InstrucoesDeSistema, T> montar) {
        return (T) renderizadas.computeIfAbsent(provedor, chave -> montar.apply(this));
    }

    /**
     * Registra a forma que o provedor já tem na mão, quando estas instruções foram lidas dela. É o
     * caminho sem perda: o provedor de origem recebe de volta exatamente o que entregou.
     */
    InstrucoesDeSistema comRenderizada(String provedor, Object forma) {
        renderizadas.put(provedor, forma);
        return this;
    }
}
