package dev.jarvis.ia.provedor;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * As ferramentas oferecidas num pedido, montadas uma vez por pedido como o
 * {@link InstrucoesDeSistema} — e pelo mesmo motivo guardam a forma que cada provedor fez delas:
 * a lista entra no prefixo do cache de prompt, e um byte diferente entre passos o derrubaria.
 *
 * @param buscaSobDemanda oferecer ao modelo a procura das definições {@code sobDemanda}
 * @param doProvedor      ferramentas que o próprio provedor executa (a busca na web), que só ele
 *                        recebe
 */
public final class ListaDeFerramentas {

    private final List<DefinicaoDeFerramenta> definicoes;
    private final boolean buscaSobDemanda;
    private final List<Bloco.Opaco> doProvedor;
    private final Map<String, Object> renderizadas = new ConcurrentHashMap<>();

    public ListaDeFerramentas(List<DefinicaoDeFerramenta> definicoes, boolean buscaSobDemanda,
            List<Bloco.Opaco> doProvedor) {
        this.definicoes = List.copyOf(definicoes);
        this.buscaSobDemanda = buscaSobDemanda;
        this.doProvedor = List.copyOf(doProvedor);
    }

    /** Sem ferramenta nenhuma: a chamada que só fala, como a narração do briefing. */
    public static ListaDeFerramentas vazia() {
        return new ListaDeFerramentas(List.of(), false, List.of());
    }

    public List<DefinicaoDeFerramenta> definicoes() {
        return definicoes;
    }

    public boolean buscaSobDemanda() {
        return buscaSobDemanda;
    }

    public List<Bloco.Opaco> doProvedor() {
        return doProvedor;
    }

    /** A forma do provedor, feita na primeira vez que ele pede e devolvida igual depois. */
    @SuppressWarnings("unchecked")
    public <T> T renderizada(String provedor, Function<ListaDeFerramentas, T> montar) {
        return (T) renderizadas.computeIfAbsent(provedor, chave -> montar.apply(this));
    }
}
