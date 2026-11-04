package dev.jarvis.tarefas;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Cancela a organização da pasta", "como está aquela?": acha a tarefa sem o modelo inventar id.
 * Com uma ativa só, a referência vazia ou dêitica ("isso", "ela") é ela; com várias, ninguém
 * adivinha — a ferramenta devolve as candidatas e o modelo pergunta qual.
 */
public final class ResolvedorDeTarefa {
    private ResolvedorDeTarefa() { }

    /** {@code tarefa} quando a escolha é única; senão as candidatas (vazia: nenhuma casou). */
    public record Escolha(TarefaDeFundo.Retrato tarefa, List<TarefaDeFundo.Retrato> candidatas) {
        public boolean unica() { return tarefa != null; }
    }

    /** Palavras que não dizem qual tarefa: dêiticos, artigos e o próprio verbo do pedido. */
    private static final Set<String> VAZIAS = Set.of("isso", "isto", "essa", "esse", "esta", "este",
            "ela", "ele", "aquela", "aquele", "aquilo", "tarefa", "tarefas", "que", "voce", "estava",
            "fazendo", "sua", "seu", "minha", "meu", "agora", "pra", "para", "com", "uma", "dos", "das",
            "como", "ali", "aqui", "cancela", "cancelar", "pausa", "pausar", "retoma",
            "retomar", "continua", "continuar", "status", "andamento");

    public static Escolha resolver(String referencia, List<TarefaDeFundo.Retrato> pool) {
        String ref = referencia == null ? "" : referencia.strip();
        for (var t : pool) if (t.id().equals(ref)) return new Escolha(t, List.of(t));
        List<String> palavras = palavras(ref).stream().filter(p -> !VAZIAS.contains(p)).toList();
        if (palavras.isEmpty())
            return pool.size() == 1 ? new Escolha(pool.getFirst(), pool) : new Escolha(null, pool);
        int melhor = 0;
        List<TarefaDeFundo.Retrato> empatadas = new ArrayList<>();
        for (var t : pool) {
            List<String> doObjetivo = palavras(t.objetivo());
            int pontos = (int) palavras.stream().filter(p -> doObjetivo.stream().anyMatch(o -> casa(p, o))).count();
            if (pontos == 0 || pontos < melhor) continue;
            if (pontos > melhor) { melhor = pontos; empatadas.clear(); }
            empatadas.add(t);
        }
        return empatadas.size() == 1 ? new Escolha(empatadas.getFirst(), empatadas) : new Escolha(null, empatadas);
    }

    /** "organização" casa com "organizar": mesma raiz de cinco letras basta para português falado. */
    private static boolean casa(String a, String b) {
        return a.equals(b) || (a.length() >= 5 && b.length() >= 5 && a.regionMatches(0, b, 0, 5));
    }

    static List<String> palavras(String texto) {
        String semAcento = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        List<String> lista = new ArrayList<>();
        for (String p : semAcento.split("[^a-z0-9]+")) if (p.length() >= 3) lista.add(p);
        return lista;
    }
}
