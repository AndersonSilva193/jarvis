package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.jarvis.util.Texto;

/**
 * Acha a peça ou o grupo de que o dono está falando: "os pistões", "o pistão esquerdo", "virabrequin".
 *
 * <p>O alvo é texto livre que o modelo copiou da fala, e os nomes são os que o outro modelo deu às
 * peças. Os dois lados passam pela mesma forma — sem acento, minúsculo, {@code _} como espaço, sem
 * artigo nem preposição e cada palavra no singular —, e a comparação é por palavra:
 *
 * <ol>
 *   <li><b>exato</b>: as mesmas palavras ("pistões" e o grupo {@code pistoes});</li>
 *   <li><b>contido</b>: todas as palavras do alvo estão no nome ("pistão" em "Pistão esquerdo");</li>
 *   <li><b>aproximado</b>: idem, aceitando uma letra de diferença em palavra de cinco ou mais.</li>
 * </ol>
 *
 * <p>Vence o nível mais alto, e grupo e peça concorrem juntos. Exato ganha de contido: com os grupos
 * {@code pistoes} e {@code pinos_do_pistao}, "pistão" vai para o primeiro. Dois candidatos no topo
 * viram {@link Empate} com as opções, e quem pergunta qual é o Jarvis — escolher sozinho seria
 * destacar a peça errada com toda a convicção.
 *
 * <p>Pura, sem Spring: o teste confere acento, plural e empate sem subir nada.
 */
public final class ResolvedorDeAlvo {

    /** Quantas opções um empate devolve: mais que isso o dono não ouve. */
    static final int MAX_OPCOES = 5;

    private static final Set<String> VAZIAS = Set.of("o", "a", "os", "as", "um", "uma", "do", "da",
            "dos", "das", "de", "no", "na", "nos", "nas", "e", "em", "esse", "essa", "este", "esta",
            "isso", "aquele", "aquela", "peca", "parte", "grupo", "todo", "toda", "todos", "todas");

    public sealed interface Resolucao { }

    /** Um grupo inteiro. */
    public record Grupo(String grupo) implements Resolucao { }

    /** As peças de um nome (as cópias de um {@code rep} dividem o nome). */
    public record Pecas(String nome, String grupo, List<String> ids) implements Resolucao { }

    /** Mais de um candidato no topo: as opções, já como se falam. */
    public record Empate(List<String> opcoes) implements Resolucao { }

    public record Nenhum() implements Resolucao { }

    private ResolvedorDeAlvo() {
    }

    public static Resolucao resolver(String alvo, List<EstadoDoHolograma.Peca> pecas) {
        List<String> procurado = palavras(alvo);
        if (procurado.isEmpty() || pecas == null || pecas.isEmpty()) {
            return new Nenhum();
        }
        List<Candidato> candidatos = candidatos(pecas);
        int melhor = 0;
        List<Candidato> topo = new ArrayList<>();
        for (Candidato candidato : candidatos) {
            int nota = nota(procurado, candidato.palavras);
            if (nota == 0 || nota < melhor) continue;
            if (nota > melhor) {
                melhor = nota;
                topo.clear();
            }
            topo.add(candidato);
        }
        if (topo.isEmpty()) {
            return new Nenhum();
        }
        topo = semRedundancia(topo);
        if (topo.size() == 1) {
            return topo.get(0).resolucao;
        }
        return new Empate(topo.stream().limit(MAX_OPCOES).map(c -> c.rotulo).toList());
    }

    /**
     * Uma peça cujo nome é igual ao do próprio grupo ("Bloco", grupo {@code bloco}) não é uma
     * segunda opção: o grupo já a contém, e perguntar "bloco ou bloco?" seria ridículo.
     */
    private static List<Candidato> semRedundancia(List<Candidato> topo) {
        Set<String> grupos = new LinkedHashSet<>();
        for (Candidato c : topo) {
            if (c.resolucao instanceof Grupo g) grupos.add(g.grupo());
        }
        List<Candidato> filtrados = new ArrayList<>();
        Set<String> rotulos = new LinkedHashSet<>();
        for (Candidato c : topo) {
            if (c.resolucao instanceof Pecas p && grupos.contains(p.grupo())) continue;
            if (rotulos.add(c.rotulo)) filtrados.add(c);
        }
        return filtrados.isEmpty() ? topo.subList(0, 1) : filtrados;
    }

    private static List<Candidato> candidatos(List<EstadoDoHolograma.Peca> pecas) {
        List<Candidato> lista = new ArrayList<>();
        Set<String> gruposVistos = new LinkedHashSet<>();
        Map<String, List<EstadoDoHolograma.Peca>> porNome = new LinkedHashMap<>();
        for (EstadoDoHolograma.Peca peca : pecas) {
            if (gruposVistos.add(peca.grupo())) {
                lista.add(new Candidato(palavras(peca.grupo()), rotuloDoGrupo(peca.grupo()),
                        new Grupo(peca.grupo())));
            }
            porNome.computeIfAbsent(Texto.normalizar(peca.nome()) + "|" + peca.grupo(), k -> new ArrayList<>())
                    .add(peca);
        }
        for (List<EstadoDoHolograma.Peca> mesmas : porNome.values()) {
            EstadoDoHolograma.Peca primeira = mesmas.get(0);
            lista.add(new Candidato(palavras(primeira.nome()), primeira.nome().strip(),
                    new Pecas(primeira.nome().strip(), primeira.grupo(),
                            mesmas.stream().map(EstadoDoHolograma.Peca::id).toList())));
        }
        return lista;
    }

    /** {@code pinos_do_pistao} → "pinos do pistao": o grupo é snake_case sem acento. */
    public static String rotuloDoGrupo(String grupo) {
        return grupo == null ? "" : grupo.replace('_', ' ').strip();
    }

    /** 3 exato, 2 contido, 1 aproximado, 0 nada. */
    private static int nota(List<String> procurado, List<String> candidato) {
        if (candidato.isEmpty()) return 0;
        if (new LinkedHashSet<>(procurado).equals(new LinkedHashSet<>(candidato))) return 3;
        if (candidato.containsAll(procurado)) return 2;
        for (String palavra : procurado) {
            if (candidato.stream().noneMatch(c -> perto(palavra, c))) return 0;
        }
        return 1;
    }

    /** Uma letra a mais, a menos ou trocada, só em palavra comprida: "eixo" e "aixo" não são a mesma. */
    static boolean perto(String a, String b) {
        if (a.equals(b)) return true;
        if (Math.min(a.length(), b.length()) < 5 || Math.abs(a.length() - b.length()) > 1) return false;
        return distancia(a, b) <= 1;
    }

    private static int distancia(String a, String b) {
        int[] anterior = new int[b.length() + 1];
        int[] atual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) anterior[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            atual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int troca = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                atual[j] = Math.min(Math.min(atual[j - 1] + 1, anterior[j] + 1), anterior[j - 1] + troca);
            }
            int[] t = anterior;
            anterior = atual;
            atual = t;
        }
        return anterior[b.length()];
    }

    /** As palavras que contam, cada uma no singular. */
    static List<String> palavras(String texto) {
        List<String> palavras = new ArrayList<>();
        for (String bruta : Texto.normalizar(texto == null ? "" : texto.replace('_', ' '))
                .split("[^a-z0-9]+")) {
            if (bruta.isEmpty() || VAZIAS.contains(bruta)) continue;
            String singular = singular(bruta);
            if (!VAZIAS.contains(singular)) palavras.add(singular);
        }
        return palavras;
    }

    /**
     * O singular de uma palavra já sem acento. Regras de plural do português, da mais específica para
     * a mais geral; erra em palavra rara, mas erra igual dos dois lados, e é isso que importa.
     */
    static String singular(String palavra) {
        if (palavra.length() <= 3) return palavra;
        if (palavra.endsWith("oes") || palavra.endsWith("aes")) return palavra.substring(0, palavra.length() - 3) + "ao";
        if (palavra.endsWith("ais")) return palavra.substring(0, palavra.length() - 3) + "al";
        if (palavra.endsWith("eis")) return palavra.substring(0, palavra.length() - 3) + "el";
        if (palavra.endsWith("ois")) return palavra.substring(0, palavra.length() - 3) + "ol";
        if (palavra.endsWith("uis")) return palavra.substring(0, palavra.length() - 3) + "ul";
        if (palavra.endsWith("ns")) return palavra.substring(0, palavra.length() - 2) + "m";
        if (palavra.endsWith("res") || palavra.endsWith("zes")) return palavra.substring(0, palavra.length() - 2);
        if (palavra.endsWith("ses") && palavra.length() > 4) return palavra.substring(0, palavra.length() - 2);
        if (palavra.endsWith("s") && !palavra.endsWith("ss")) return palavra.substring(0, palavra.length() - 1);
        return palavra;
    }

    private record Candidato(List<String> palavras, String rotulo, Resolucao resolucao) { }
}
