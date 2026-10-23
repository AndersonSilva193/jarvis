package dev.jarvis.util;

import java.util.TreeSet;

import tools.jackson.databind.JsonNode;

/**
 * Pequenos atalhos para lidar com JSON vindo de fora: ler um valor sem estourar exceção quando o
 * campo não existe, e escrever um nó em forma estável quando ele vai virar chave de comparação.
 */
public final class Json {

    private Json() {
    }

    public static String texto(JsonNode no) {
        return no != null && no.isString() ? no.stringValue() : "";
    }

    public static long numero(JsonNode no) {
        return no != null && no.isNumber() ? no.longValue() : 0L;
    }

    /**
     * Um número com casas, zero quando o campo falta.
     *
     * No Jackson 3 o {@code doubleValue()} de um nó ausente <b>estoura</b>, e é por isso que ele
     * nunca deve ser chamado direto em JSON vindo de fora — nem de um arquivo que o próprio Jarvis
     * gravou, porque uma versão anterior pode não ter escrito aquele campo.
     */
    public static double decimal(JsonNode no) {
        return no != null && no.isNumber() ? no.doubleValue() : 0d;
    }

    /** Um booleano, falso quando o campo falta. Mesmo motivo do {@link #decimal}. */
    public static boolean booleano(JsonNode no) {
        return no != null && no.isBoolean() && no.booleanValue();
    }

    /**
     * JSON em forma estável: chaves de objeto em ordem, arrays na ordem que vieram.
     *
     * <p>O {@code JsonMapper} do projeto é cru, sem ordenação de chaves, então o {@code toString()}
     * de um nó sai na ordem em que o campo chegou. Quem compara duas entradas para decidir se são
     * "a mesma chamada" precisa desta forma, e não daquela: {@code {"x":1,"y":2}} e
     * {@code {"y":2,"x":1}} são o mesmo pedido escrito de dois jeitos.
     */
    public static String canonico(JsonNode no) {
        StringBuilder texto = new StringBuilder();
        escrever(no, texto);
        return texto.toString();
    }

    private static void escrever(JsonNode no, StringBuilder texto) {
        if (no == null || no.isMissingNode() || no.isNull()) {
            texto.append("null");
            return;
        }
        if (no.isObject()) {
            texto.append('{');
            boolean primeiro = true;
            for (String nome : new TreeSet<>(no.propertyNames())) {
                if (!primeiro) texto.append(',');
                primeiro = false;
                texto.append('"').append(nome).append("\":");
                escrever(no.get(nome), texto);
            }
            texto.append('}');
            return;
        }
        if (no.isArray()) {
            texto.append('[');
            boolean primeiro = true;
            for (JsonNode item : no.values()) {
                if (!primeiro) texto.append(',');
                primeiro = false;
                escrever(item, texto);
            }
            texto.append(']');
            return;
        }
        texto.append(no.toString());
    }
}
