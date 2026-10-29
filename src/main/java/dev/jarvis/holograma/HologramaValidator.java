package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;

/**
 * Transforma a peça compacta do modelo em {@link HologramaPeca}s prontas: expande {@code rep} e
 * {@code rad}, conserta o que dá para consertar e descarta o que não dá, sempre dizendo por quê.
 *
 * <p><b>Uma instância por objeto.</b> Ela guarda os ids já usados e o total de peças, que são
 * regras do objeto inteiro; não é thread-safe, e não precisa — um objeto é lido em sequência.
 *
 * <p>A régua é a do {@code MontadorDeCardDePesquisa}: o modelo escreve número, e número de modelo
 * é palpite. Então tudo é limitado aqui (posição em ±{@value #LIMITE_POSICAO}, tamanho entre
 * {@value #TAMANHO_MINIMO} e {@value #TAMANHO_MAXIMO}), e o erro pequeno vira um valor seguro em
 * vez de uma peça a menos — forma desconhecida vira {@code box}, tamanho faltando vira o padrão da
 * forma, cor torta vira o ciano. O que não tem conserto é descartado: {@code tube} e {@code line}
 * sem dois pontos não têm o que desenhar.
 *
 * <p>Repetição, como fica decidido em {@code docs/HOLOGRAMA.md}:
 * <ul>
 * <li>{@code rep {"n":4,"d":[dx,dy,dz]}} — {@code n} cópias, contando a original, a cópia
 * {@code i} deslocada {@code i·d};</li>
 * <li>{@code rad {"n":12,"r":2,"ax":"y","a":0}} — {@code n} cópias num círculo de raio {@code r}
 * em volta de {@code p}, no plano perpendicular ao eixo, começando no ângulo {@code a}; cada
 * cópia gira junto — rotação e direção de explosão —, para acompanhar o círculo;</li>
 * <li>os dois juntos — uma hélice: {@code rep.n} cópias, a cópia {@code i} deslocada {@code i·d}
 * e girada {@code a + i·360/rad.n}. É o que faz os pares de bases do DNA numa linha só.</li>
 * </ul>
 * As cópias recebem ids {@code id_1..id_n}.
 */
public final class HologramaValidator {

    public static final int MAX_PECAS = 250;
    static final double LIMITE_POSICAO = 6;
    static final double TAMANHO_MINIMO = 0.01;
    static final double TAMANHO_MAXIMO = 8;
    static final double OPACIDADE_MINIMA = 0.05;
    static final int MAX_COPIAS = 60;
    static final int MAX_PONTOS = 32;
    static final int MAX_ID = 40;
    static final int MAX_NOME = 60;
    static final String COR_PADRAO = "#38bdf8";
    static final String GRUPO_PADRAO = "geral";

    /** O tamanho padrão de cada forma, que também diz quantos números o {@code s} dela tem. */
    private static final Map<String, double[]> PADROES = padroes();

    /** As formas que a HUD sabe desenhar, na ordem do prompt. */
    public static final Set<String> FORMAS = Collections.unmodifiableSet(PADROES.keySet());

    private static final Pattern COR = Pattern.compile("#[0-9a-f]{6}");
    private static final Pattern COR_CURTA = Pattern.compile("#[0-9a-f]{3}");
    private static final Pattern FORA_DO_ID = Pattern.compile("[^a-z0-9]+");
    private static final double[] PARA_CIMA = { 0, 1, 0 };

    private final Set<String> ids = new HashSet<>();
    private int total;
    private int descartadasPeloLimite;

    /** O que uma linha virou: as peças (zero, uma ou as cópias) e o que foi consertado ou descartado. */
    public record Validacao(List<HologramaPeca> pecas, List<String> avisos) {
    }

    public Validacao validar(JsonNode compacta) {
        List<String> avisos = new ArrayList<>();
        if (compacta == null || !compacta.isObject()) {
            return new Validacao(List.of(), List.of("Linha de peça sem objeto JSON; descartada."));
        }
        String idBase = slug(Json.texto(compacta.get("id")));
        if (idBase.isEmpty()) {
            idBase = "peca";
        }
        if (total >= MAX_PECAS) {
            return new Validacao(List.of(), descartarPeloLimite(1));
        }

        String forma = Json.texto(compacta.get("f")).strip().toLowerCase(Locale.ROOT);
        // Ausente é o padrão, como o tamanho ausente; escrita errada é engano, e merece aviso.
        if (forma.isEmpty()) {
            forma = "box";
        } else if (!PADROES.containsKey(forma)) {
            avisos.add("Peça " + idBase + ": forma '" + HologramaLineParser.limpo(forma, 20)
                    + "' desconhecida, virou box.");
            forma = "box";
        }

        double[][] pontos = new double[0][];
        if (forma.equals("tube") || forma.equals("line")) {
            pontos = pontos(compacta.get("pts"));
            if (pontos.length < 2) {
                avisos.add("Peça " + idBase + ": " + forma + " precisa de 2 pontos ou mais; descartada.");
                return new Validacao(List.of(), avisos);
            }
            if (pontos.length > MAX_PONTOS) {
                avisos.add("Peça " + idBase + ": " + pontos.length + " pontos, ficaram os "
                        + MAX_PONTOS + " primeiros.");
                pontos = Arrays.copyOf(pontos, MAX_PONTOS);
            }
        }

        String nome = HologramaLineParser.limpo(Json.texto(compacta.get("n")), MAX_NOME);
        String grupo = slug(Json.texto(compacta.get("g")));
        double[] posicao = vetor(compacta.get("p"), LIMITE_POSICAO);
        double[] rotacao = vetor(compacta.get("r"), Double.MAX_VALUE);
        double[] tamanho = tamanho(forma, compacta.get("s"));
        String cor = cor(Json.texto(compacta.get("c")));
        double[] explosao = direcao(vetor(compacta.get("e"), Double.MAX_VALUE), posicao);
        double opacidade = opacidade(compacta.get("o"));
        JsonNode ct = compacta.get("ct");
        boolean contorno = ct == null || !ct.isBoolean() || ct.booleanValue();

        Repeticao repeticao = repeticao(compacta.get("rep"), compacta.get("rad"));
        int copias = repeticao.copias();
        int cabem = MAX_PECAS - total;
        if (copias > cabem) {
            avisos.add("Peça " + idBase + ": couberam " + cabem + " de " + copias + " cópias.");
            avisos.addAll(descartarPeloLimite(copias - cabem));
            copias = cabem;
        }

        List<HologramaPeca> pecas = new ArrayList<>(copias);
        for (int i = 0; i < copias; i++) {
            String id = unico(repeticao.copias() > 1 ? sufixado(idBase, "_" + (i + 1)) : idBase, avisos);
            double[] onde = posicao.clone();
            double[] giro = rotacao;
            double[] afastamento = explosao.clone();
            for (int k = 0; k < 3; k++) {
                onde[k] += i * repeticao.deslocamento()[k];
            }
            if (repeticao.circular()) {
                double angulo = repeticao.anguloInicial() + i * 360.0 / repeticao.porVolta();
                double[] raio = Rotacoes.girar(repeticao.eixo(), angulo, base(repeticao.eixo(), repeticao.raio()));
                for (int k = 0; k < 3; k++) {
                    onde[k] += raio[k];
                }
                giro = Rotacoes.compor(repeticao.eixo(), angulo, rotacao);
                // A direção de explosão gira junto: dente de engrenagem se afasta para fora do círculo.
                afastamento = arredondado(Rotacoes.girar(repeticao.eixo(), angulo, explosao));
            }
            pecas.add(new HologramaPeca(id, nome.isEmpty() ? id : nome,
                    grupo.isEmpty() ? GRUPO_PADRAO : grupo, forma, arredondado(limitado(onde, LIMITE_POSICAO)),
                    angulos(giro), tamanho.clone(), cor, afastamento, opacidade, contorno,
                    copiaDe(pontos)));
        }
        total += pecas.size();
        return new Validacao(pecas, avisos);
    }

    /** Quantas peças já foram aceitas neste objeto. */
    public int total() {
        return total;
    }

    /** Quantas peças (contando cópias) ficaram de fora por causa do {@link #MAX_PECAS}. */
    public int descartadasPeloLimite() {
        return descartadasPeloLimite;
    }

    /** Avisa uma vez só: depois do limite, cada linha descartada a mais é ruído. */
    private List<String> descartarPeloLimite(int quantas) {
        boolean primeira = descartadasPeloLimite == 0;
        descartadasPeloLimite += quantas;
        return primeira
                ? List.of("Limite de " + MAX_PECAS + " peças atingido; o resto do objeto foi descartado.")
                : List.of();
    }

    private record Repeticao(int copias, double[] deslocamento, boolean circular, int porVolta,
            double raio, char eixo, double anguloInicial) {
    }

    private static Repeticao repeticao(JsonNode rep, JsonNode rad) {
        double[] nada = { 0, 0, 0 };
        int nRep = rep != null && rep.isObject() ? copias(rep.get("n")) : 1;
        double[] d = nRep > 1 ? vetor(rep.get("d"), LIMITE_POSICAO) : nada;
        int nRad = rad != null && rad.isObject() ? copias(rad.get("n")) : 1;
        if (nRad <= 1) {
            return new Repeticao(nRep, d, false, 1, 0, 'y', 0);
        }
        double raio = Math.max(0, Math.min(LIMITE_POSICAO, Json.decimal(rad.get("r"))));
        String ax = Json.texto(rad.get("ax")).strip().toLowerCase(Locale.ROOT);
        char eixo = ax.equals("x") || ax.equals("z") ? ax.charAt(0) : 'y';
        double a = finito(Json.decimal(rad.get("a")), 0);
        // Os dois juntos são hélice: quem conta as cópias é o rep, e o rad diz quantas por volta.
        return new Repeticao(nRep > 1 ? nRep : nRad, d, true, nRad, raio, eixo, a);
    }

    private static int copias(JsonNode n) {
        double valor = Json.decimal(n);
        return Double.isFinite(valor) ? (int) Math.max(1, Math.min(MAX_COPIAS, Math.round(valor))) : 1;
    }

    /**
     * O vetor que o {@code rad} gira para chegar ao círculo: o ângulo zero é o {@code +x} nos
     * eixos {@code y} e {@code z}, e o {@code +y} no eixo {@code x}.
     */
    private static double[] base(char eixo, double raio) {
        return eixo == 'x' ? new double[] { 0, raio, 0 } : new double[] { raio, 0, 0 };
    }

    private String unico(String id, List<String> avisos) {
        String escolhido = id;
        for (int k = 2; ids.contains(escolhido); k++) {
            escolhido = sufixado(id, "_" + k);
        }
        if (!escolhido.equals(id)) {
            avisos.add("Id '" + id + "' repetido; virou '" + escolhido + "'.");
        }
        ids.add(escolhido);
        return escolhido;
    }

    private static String sufixado(String id, String sufixo) {
        return id.substring(0, Math.min(id.length(), MAX_ID - sufixo.length())) + sufixo;
    }

    /** "Pistão Esquerdo" → "pistao_esquerdo": sem acento, minúsculo, só letra, número e _. */
    static String slug(String bruto) {
        String texto = FORA_DO_ID.matcher(Texto.semAcento(bruto).toLowerCase(Locale.ROOT)).replaceAll("_");
        texto = texto.replaceAll("^_+|_+$", "");
        return texto.length() <= MAX_ID ? texto : texto.substring(0, MAX_ID).replaceAll("_+$", "");
    }

    /** Três números; o que faltar ou não for número finito vira zero. */
    private static double[] vetor(JsonNode no, double limite) {
        double[] v = new double[3];
        for (int k = 0; k < 3; k++) {
            JsonNode item = no != null && no.isArray() ? no.get(k) : null;
            v[k] = Math.max(-limite, Math.min(limite, finito(Json.decimal(item), 0)));
        }
        return v;
    }

    private static double[] tamanho(String forma, JsonNode s) {
        double[] padrao = PADROES.get(forma);
        double[] t = new double[padrao.length];
        for (int k = 0; k < t.length; k++) {
            JsonNode item = s != null && s.isArray() ? s.get(k) : null;
            double valor = item != null && item.isNumber() ? finito(item.doubleValue(), padrao[k]) : padrao[k];
            t[k] = arredondar(Math.max(TAMANHO_MINIMO, Math.min(TAMANHO_MAXIMO, valor)));
        }
        // Anel com raio interno maior que o externo é o mesmo anel escrito ao contrário.
        if (forma.equals("ring") && t[0] >= t[1]) {
            double interno = Math.min(t[0], t[1]), externo = Math.max(t[0], t[1]);
            t[0] = interno;
            t[1] = externo > interno ? externo : Math.min(TAMANHO_MAXIMO, interno + TAMANHO_MINIMO);
        }
        return t;
    }

    private static double[][] pontos(JsonNode pts) {
        if (pts == null || !pts.isArray()) {
            return new double[0][];
        }
        List<double[]> lista = new ArrayList<>();
        for (JsonNode ponto : pts) {
            if (ponto.isArray() && ponto.size() >= 3 && ponto.get(0).isNumber() && ponto.get(1).isNumber()
                    && ponto.get(2).isNumber()) {
                lista.add(arredondado(vetor(ponto, LIMITE_POSICAO)));
            }
        }
        return lista.toArray(double[][]::new);
    }

    static String cor(String bruta) {
        String cor = bruta.strip().toLowerCase(Locale.ROOT);
        if (COR.matcher(cor).matches()) {
            return cor;
        }
        if (COR_CURTA.matcher(cor).matches()) {
            return "#" + cor.charAt(1) + cor.charAt(1) + cor.charAt(2) + cor.charAt(2) + cor.charAt(3) + cor.charAt(3);
        }
        return COR_PADRAO;
    }

    private static double opacidade(JsonNode o) {
        if (o == null || !o.isNumber()) {
            return 1;
        }
        return arredondar(Math.max(OPACIDADE_MINIMA, Math.min(1, finito(o.doubleValue(), 1))));
    }

    /**
     * A direção unitária da vista explodida. Sem direção, a peça se afasta do centro do objeto
     * pela própria posição; no centro exato, sobe.
     */
    private static double[] direcao(double[] e, double[] posicao) {
        double[] v = comprimento(e) > 1e-9 ? e : comprimento(posicao) > 1e-9 ? posicao : PARA_CIMA;
        double c = comprimento(v);
        return arredondado(new double[] { v[0] / c, v[1] / c, v[2] / c });
    }

    private static double comprimento(double[] v) {
        return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    /** Graus em (-180, 180], arredondados. */
    private static double[] angulos(double[] graus) {
        double[] r = new double[3];
        for (int k = 0; k < 3; k++) {
            double a = graus[k] % 360;
            if (a <= -180) {
                a += 360;
            } else if (a > 180) {
                a -= 360;
            }
            r[k] = arredondar(a);
        }
        return r;
    }

    private static double[] limitado(double[] v, double limite) {
        for (int k = 0; k < v.length; k++) {
            v[k] = Math.max(-limite, Math.min(limite, v[k]));
        }
        return v;
    }

    private static double[] arredondado(double[] v) {
        for (int k = 0; k < v.length; k++) {
            v[k] = arredondar(v[k]);
        }
        return v;
    }

    /** Quatro casas: o JSON fica curto e o {@code -0.0} vira {@code 0.0}. */
    static double arredondar(double v) {
        return Math.round(v * 10_000) / 10_000.0 + 0.0;
    }

    private static double finito(double valor, double padrao) {
        return Double.isFinite(valor) ? valor : padrao;
    }

    private static double[][] copiaDe(double[][] pontos) {
        double[][] copia = new double[pontos.length][];
        for (int k = 0; k < pontos.length; k++) {
            copia[k] = pontos[k].clone();
        }
        return copia;
    }

    private static Map<String, double[]> padroes() {
        Map<String, double[]> m = new LinkedHashMap<>();
        m.put("box", new double[] { 1, 1, 1 });
        m.put("sph", new double[] { 0.5 });
        m.put("cyl", new double[] { 0.5, 0.5, 1 });
        m.put("cone", new double[] { 0.5, 1 });
        m.put("tor", new double[] { 1, 0.1 });
        m.put("ring", new double[] { 0.6, 1 });
        m.put("cap", new double[] { 0.3, 1 });
        m.put("tube", new double[] { 0.05 });
        m.put("line", new double[0]);
        m.put("helix", new double[] { 0.5, 2, 3, 0.05 });
        return Collections.unmodifiableMap(m);
    }
}
