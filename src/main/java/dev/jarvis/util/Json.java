package dev.jarvis.util;

import tools.jackson.databind.JsonNode;

/** Pequenos atalhos para ler valores de um JSON sem estourar exceção quando o campo não existe. */
public final class Json {

    private Json() {
    }

    public static String texto(JsonNode no) {
        return no != null && no.isString() ? no.stringValue() : "";
    }

    public static long numero(JsonNode no) {
        return no != null && no.isNumber() ? no.longValue() : 0L;
    }
}
