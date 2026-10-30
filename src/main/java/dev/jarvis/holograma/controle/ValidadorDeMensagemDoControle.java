package dev.jarvis.holograma.controle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O formato das mensagens do canal do holograma. Nada passa adiante cru: cada evento é lido campo a
 * campo, conferido pela lista fechada da seção 11 do {@code docs/HOLOGRAMA.md} e reescrito.
 *
 * <p>Um quadro do celular é um objeto, ou um array de até {@value #MAX_EVENTOS} objetos quando o
 * mesmo intervalo de 33 ms juntou mais de um tipo. Qualquer evento torto reprova o quadro inteiro:
 * o celular é código nosso, então quadro torto é defeito ou é alguém fora da HUD.
 */
@Component
public class ValidadorDeMensagemDoControle {

    public static final int MAX_CARACTERES = 512;
    public static final int MAX_EVENTOS = 8;
    static final int MAX_ROTULO = 24;

    /** O que o celular disse sobre a latência que mediu: vai para o log, não para a HUD. */
    public record Latencia(double p50, double p95, int amostras) { }

    public record Quadro(List<ObjectNode> eventos, Optional<Latencia> latencia) { }

    /** Os campos de cada tipo, exatos: campo a mais ou a menos reprova. */
    private static final Map<String, Set<String>> CAMPOS = Map.ofEntries(
            Map.entry("girar", Set.of("dx", "dy")),
            Map.entry("soltar", Set.of("vx", "vy")),
            Map.entry("zoom", Set.of("f")),
            Map.entry("explodir", Set.of()),
            Map.entry("cursor", Set.of("x", "y")),
            Map.entry("cursorFim", Set.of()),
            Map.entry("selecionar", Set.of()),
            Map.entry("resetar", Set.of()),
            Map.entry("mao", Set.of("v")),
            Map.entry("toque", Set.of("v")),
            Map.entry("gesto", Set.of("n")),
            Map.entry("eco", Set.of("n")),
            Map.entry("latencia", Set.of("p50", "p95", "k")));

    private final JsonMapper json;

    public ValidadorDeMensagemDoControle(JsonMapper json) {
        this.json = json;
    }

    public Optional<Quadro> doControle(String texto) {
        Optional<JsonNode> lido = ler(texto);
        if (lido.isEmpty()) {
            return Optional.empty();
        }
        JsonNode raiz = lido.get();
        List<JsonNode> brutos = new ArrayList<>();
        if (raiz.isArray() && raiz.size() >= 1 && raiz.size() <= MAX_EVENTOS) {
            raiz.forEach(brutos::add);
        } else if (raiz.isObject()) {
            brutos.add(raiz);
        } else {
            return Optional.empty();
        }
        List<ObjectNode> eventos = new ArrayList<>();
        Latencia latencia = null;
        for (JsonNode bruto : brutos) {
            Optional<ObjectNode> evento = evento(bruto);
            if (evento.isEmpty()) {
                return Optional.empty();
            }
            ObjectNode e = evento.get();
            if ("latencia".equals(e.path("t").stringValue())) {
                latencia = new Latencia(e.path("p50").doubleValue(), e.path("p95").doubleValue(), e.path("k").intValue());
            } else {
                eventos.add(e);
            }
        }
        return Optional.of(new Quadro(List.copyOf(eventos), Optional.ofNullable(latencia)));
    }

    /** O que a HUD manda: {@code parear}, {@code desconectar} ou a resposta de um {@code eco}. */
    public Optional<ObjectNode> daHud(String texto) {
        Optional<JsonNode> lido = ler(texto).filter(JsonNode::isObject);
        if (lido.isEmpty()) {
            return Optional.empty();
        }
        JsonNode no = lido.get();
        String tipo = no.path("t").isString() ? no.path("t").stringValue() : "";
        return switch (tipo) {
            case "parear", "desconectar" -> no.size() == 1 ? Optional.of(json.createObjectNode().put("t", tipo))
                    : Optional.empty();
            case "eco" -> evento(no);
            default -> Optional.empty();
        };
    }

    private Optional<JsonNode> ler(String texto) {
        if (texto == null || texto.isEmpty() || texto.length() > MAX_CARACTERES) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(json.readTree(texto));
        } catch (JacksonException e) {
            return Optional.empty();
        }
    }

    private Optional<ObjectNode> evento(JsonNode no) {
        if (!no.isObject() || !no.path("t").isString()) {
            return Optional.empty();
        }
        String tipo = no.path("t").stringValue();
        Set<String> campos = CAMPOS.get(tipo);
        if (campos == null) {
            return Optional.empty();
        }
        if (tipo.equals("explodir")) {
            return explodir(no);
        }
        if (no.size() != campos.size() + 1 || !campos.stream().allMatch(no::has)) {
            return Optional.empty();
        }
        ObjectNode saida = json.createObjectNode().put("t", tipo);
        boolean ok = switch (tipo) {
            case "girar" -> numero(no, "dx", -1, 1, saida) && numero(no, "dy", -1, 1, saida);
            case "soltar" -> numero(no, "vx", -20, 20, saida) && numero(no, "vy", -20, 20, saida);
            case "zoom" -> numero(no, "f", 0.5, 2, saida);
            case "cursor" -> numero(no, "x", 0, 1, saida) && numero(no, "y", 0, 1, saida);
            case "mao", "toque" -> zeroOuUm(no, saida);
            case "gesto" -> rotulo(no, saida);
            case "eco" -> inteiro(no, "n", 0, Integer.MAX_VALUE, saida);
            case "latencia" -> numero(no, "p50", 0, 60_000, saida) && numero(no, "p95", 0, 60_000, saida)
                    && inteiro(no, "k", 0, 10_000, saida);
            default -> true;
        };
        return ok ? Optional.of(saida) : Optional.empty();
    }

    /** {@code v} absoluto (câmera) ou {@code dv} somado ao atual (touchpad), um dos dois. */
    private Optional<ObjectNode> explodir(JsonNode no) {
        if (no.size() != 2) {
            return Optional.empty();
        }
        ObjectNode saida = json.createObjectNode().put("t", "explodir");
        boolean ok = no.has("v") ? numero(no, "v", 0, 1, saida) : no.has("dv") && numero(no, "dv", -1, 1, saida);
        return ok ? Optional.of(saida) : Optional.empty();
    }

    private static boolean numero(JsonNode no, String campo, double min, double max, ObjectNode saida) {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isNumber()) {
            return false;
        }
        double d = valor.doubleValue();
        if (!Double.isFinite(d) || d < min || d > max) {
            return false;
        }
        saida.put(campo, d);
        return true;
    }

    private static boolean inteiro(JsonNode no, String campo, long min, long max, ObjectNode saida) {
        JsonNode valor = no.get(campo);
        if (valor == null || !valor.isIntegralNumber() || valor.longValue() < min || valor.longValue() > max) {
            return false;
        }
        saida.put(campo, valor.intValue());
        return true;
    }

    private static boolean zeroOuUm(JsonNode no, ObjectNode saida) {
        JsonNode v = no.get("v");
        if (v == null || !v.isIntegralNumber() || (v.intValue() != 0 && v.intValue() != 1)) {
            return false;
        }
        saida.put("v", v.intValue());
        return true;
    }

    /** O rótulo do gesto vai para a tela: só letras, números, espaço e um pouco de pontuação. */
    private static boolean rotulo(JsonNode no, ObjectNode saida) {
        JsonNode n = no.get("n");
        if (n == null || !n.isString()) {
            return false;
        }
        String texto = n.stringValue().strip();
        if (texto.isEmpty() || texto.length() > MAX_ROTULO || !texto.matches("[\\p{L}\\p{N} .,…·%()-]+")) {
            return false;
        }
        saida.put("n", texto);
        return true;
    }
}
