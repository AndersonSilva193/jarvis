package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Acha na galeria o objeto de que o dono está falando, antes de alguém gastar modelo gerando outro.
 *
 * <p>A chave do cache ({@link GaleriaDeHologramas#chave}) é exata de propósito — ela decide se o
 * {@code /gerar} reproduz em vez de gerar —, e por isso não serve à conversa: "abre o átomo de carbono
 * da galeria" não é a chave de nada, o mesmo objeto em outro nível é outra chave, e os exemplos fixos
 * nem respondem a pedido. Aqui a comparação é por palavra, a do {@link ResolvedorDeAlvo} (sem acento,
 * singular, sem artigo), contra o <b>nome</b> que o modelo deu ao objeto e contra o <b>pedido</b> que o
 * gerou, e as palavras de quem fala da galeria ("abre", "galeria", "holograma") não contam.
 *
 * <ol>
 *   <li><b>exato</b>: as mesmas palavras;</li>
 *   <li><b>contido</b>: o pedido inteiro está no item ("carbono" acha "Átomo de carbono"). Só nessa
 *       direção: "motor V8 turbo" não pode abrir o "Motor V8" salvo — a palavra a mais é o pedido;</li>
 *   <li><b>aproximado</b>: uma letra de diferença por palavra comprida.</li>
 * </ol>
 *
 * <p>No topo, o mesmo nível de detalhe pedido vence, e depois o mais novo (a ordem da listagem). Dois
 * objetos <b>diferentes</b> no topo são {@link Varios}: quem pergunta qual é o Jarvis. Pura, para o
 * teste.
 */
public final class BuscaNaGaleria {

    /** Quem fala da galeria fala assim; nenhuma destas palavras diz qual é o objeto. */
    private static final Set<String> DA_CONVERSA = Set.of("abre", "abrir", "abra", "mostra", "mostrar",
            "carrega", "carregar", "coloca", "poe", "bota", "galeria", "holograma", "objeto", "modelo",
            "salvo", "guardado", "que", "voce", "fez", "tinha", "ja", "aquele", "aquela", "meu", "minha",
            "jarvis", "favor", "por", "pra", "para", "ai");

    static final int MAX_OPCOES = 4;

    public sealed interface Resultado { }

    public record Achado(GaleriaDeHologramas.Item item) implements Resultado { }

    /** Objetos diferentes no topo: os nomes, para o Jarvis perguntar. */
    public record Varios(List<GaleriaDeHologramas.Item> itens) implements Resultado { }

    public record Nada() implements Resultado { }

    private BuscaNaGaleria() {
    }

    /**
     * @param itens     a listagem da galeria, na ordem dela (exemplos, depois do mais novo ao mais velho)
     * @param preferido o nível pedido; vence o empate, mas outro nível também serve — o objeto existe
     */
    public static Resultado procurar(String pedido, DetalheHolograma preferido, List<GaleriaDeHologramas.Item> itens) {
        List<String> procurado = palavras(pedido);
        if (procurado.isEmpty() || itens == null || itens.isEmpty()) return new Nada();
        int melhor = 0;
        List<GaleriaDeHologramas.Item> topo = new ArrayList<>();
        for (GaleriaDeHologramas.Item item : itens) {
            int nota = Math.max(nota(procurado, palavras(item.nome())), nota(procurado, palavras(item.objeto())));
            if (nota == 0 || nota < melhor) continue;
            if (nota > melhor) {
                melhor = nota;
                topo.clear();
            }
            topo.add(item);
        }
        if (topo.isEmpty()) return new Nada();
        // O mesmo objeto em níveis diferentes não é dúvida: fica o do nível pedido, ou o mais novo.
        Map<String, GaleriaDeHologramas.Item> porObjeto = new LinkedHashMap<>();
        for (GaleriaDeHologramas.Item item : topo) {
            String chave = String.join(" ", new LinkedHashSet<>(palavras(item.nome())));
            GaleriaDeHologramas.Item atual = porObjeto.get(chave);
            if (atual == null || (!preferido.chave().equals(atual.detalhe()) && preferido.chave().equals(item.detalhe()))) {
                porObjeto.put(chave, item);
            }
        }
        List<GaleriaDeHologramas.Item> distintos = List.copyOf(porObjeto.values());
        return distintos.size() == 1 ? new Achado(distintos.get(0))
                : new Varios(distintos.subList(0, Math.min(MAX_OPCOES, distintos.size())));
    }

    /** 3 exato, 2 o pedido contido no item, 1 aproximado, 0 nada. */
    private static int nota(List<String> procurado, List<String> item) {
        if (item.isEmpty()) return 0;
        Set<String> a = new LinkedHashSet<>(procurado);
        Set<String> b = new LinkedHashSet<>(item);
        if (a.equals(b)) return 3;
        if (b.containsAll(a)) return 2;
        for (String palavra : a) {
            if (b.stream().noneMatch(p -> ResolvedorDeAlvo.perto(palavra, p))) return 0;
        }
        return 1;
    }

    static List<String> palavras(String texto) {
        List<String> palavras = new ArrayList<>();
        for (String palavra : ResolvedorDeAlvo.palavras(texto)) {
            if (!DA_CONVERSA.contains(palavra)) palavras.add(palavra);
        }
        return palavras;
    }
}
