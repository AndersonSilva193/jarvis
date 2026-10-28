package dev.jarvis.util;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tools.jackson.databind.JsonNode;

/**
 * Confere um valor contra o {@code esquemaEntrada()} de uma ferramenta.
 *
 * <p>Não é um validador de JSON Schema inteiro, e é de propósito: cobre as palavras-chave que os
 * esquemas do Jarvis usam — {@code type}, {@code properties}, {@code required}, {@code enum},
 * {@code const}, {@code minimum}/{@code maximum}, {@code minLength}/{@code maxLength},
 * {@code pattern}, {@code items} e {@code minItems}/{@code maxItems}. Palavra-chave que ele não
 * conhece é ignorada: estrito onde sabe, permissivo onde não sabe. Recusar o que não se entende
 * mandaria de volta ao modelo uma chamada que a ferramenta aceitaria.
 *
 * <p>Existe para o modelo local, que erra o formato com muito mais frequência que o Claude: a
 * chamada torta volta a ele como erro antes de chegar a qualquer ferramenta.
 */
public final class ValidadorDeEsquema {

    private ValidadorDeEsquema() {
    }

    /**
     * O primeiro problema, numa frase curta montada aqui, ou vazio quando o valor bate. A frase
     * vai de volta ao modelo, então nomeia o campo e o que faltou — nunca repete o valor recebido.
     */
    public static Optional<String> problema(JsonNode valor, JsonNode esquema) {
        return Optional.ofNullable(conferir(valor, esquema, "a entrada"));
    }

    private static String conferir(JsonNode valor, JsonNode esquema, String onde) {
        if (esquema == null || !esquema.isObject()) {
            return null;
        }
        JsonNode tipo = esquema.path("type");
        if (tipo.isString() && !doTipo(valor, tipo.stringValue())) {
            return onde + " deveria ser " + nomeDoTipo(tipo.stringValue());
        }
        JsonNode constante = esquema.path("const");
        if (!constante.isMissingNode() && !igual(valor, constante)) {
            return onde + " deveria ser " + constante;
        }
        JsonNode opcoes = esquema.path("enum");
        if (opcoes.isArray() && !contem(opcoes, valor)) {
            return onde + " tem de ser um destes: " + opcoes;
        }
        if (valor.isNumber()) {
            return numero(valor, esquema, onde);
        }
        if (valor.isString()) {
            return texto(valor.stringValue(), esquema, onde);
        }
        if (valor.isArray()) {
            return lista(valor, esquema, onde);
        }
        if (valor.isObject()) {
            return objeto(valor, esquema, onde);
        }
        return null;
    }

    private static String numero(JsonNode valor, JsonNode esquema, String onde) {
        BigDecimal n = valor.decimalValue();
        JsonNode minimo = esquema.path("minimum");
        if (minimo.isNumber() && n.compareTo(minimo.decimalValue()) < 0) {
            return onde + " tem de ser no mínimo " + minimo;
        }
        JsonNode maximo = esquema.path("maximum");
        if (maximo.isNumber() && n.compareTo(maximo.decimalValue()) > 0) {
            return onde + " tem de ser no máximo " + maximo;
        }
        return null;
    }

    private static String texto(String valor, JsonNode esquema, String onde) {
        int tamanho = valor.codePointCount(0, valor.length());
        JsonNode minimo = esquema.path("minLength");
        if (minimo.isIntegralNumber() && tamanho < minimo.asInt()) {
            return onde + " precisa de ao menos " + minimo.asInt() + " caractere(s)";
        }
        JsonNode maximo = esquema.path("maxLength");
        if (maximo.isIntegralNumber() && tamanho > maximo.asInt()) {
            return onde + " passou de " + maximo.asInt() + " caracteres";
        }
        JsonNode padrao = esquema.path("pattern");
        if (padrao.isString()) {
            try {
                // JSON Schema procura o padrão em qualquer ponto do texto: find(), não matches().
                if (!Pattern.compile(padrao.stringValue()).matcher(valor).find()) {
                    return onde + " está fora do formato esperado";
                }
            } catch (PatternSyntaxException ignorada) {
                // Padrão que o Java não lê não é motivo para recusar a chamada.
            }
        }
        return null;
    }

    private static String lista(JsonNode valor, JsonNode esquema, String onde) {
        JsonNode minimo = esquema.path("minItems");
        if (minimo.isIntegralNumber() && valor.size() < minimo.asInt()) {
            return onde + " precisa de ao menos " + minimo.asInt() + " item(ns)";
        }
        JsonNode maximo = esquema.path("maxItems");
        if (maximo.isIntegralNumber() && valor.size() > maximo.asInt()) {
            return onde + " passou de " + maximo.asInt() + " itens";
        }
        JsonNode itens = esquema.path("items");
        for (int i = 0; i < valor.size(); i++) {
            String problema = conferir(valor.get(i), itens, onde + "[" + i + "]");
            if (problema != null) {
                return problema;
            }
        }
        return null;
    }

    private static String objeto(JsonNode valor, JsonNode esquema, String onde) {
        for (JsonNode obrigatorio : esquema.path("required")) {
            String campo = obrigatorio.asString("");
            if (!campo.isEmpty() && (valor.path(campo).isMissingNode() || valor.path(campo).isNull())) {
                return "falta o campo '" + campo + "'";
            }
        }
        for (var propriedade : esquema.path("properties").properties()) {
            JsonNode campo = valor.path(propriedade.getKey());
            if (campo.isMissingNode()) {
                continue;
            }
            String problema = conferir(campo, propriedade.getValue(),
                    "o campo '" + propriedade.getKey() + "'");
            if (problema != null) {
                return problema;
            }
        }
        return null;
    }

    private static boolean doTipo(JsonNode valor, String tipo) {
        return switch (tipo) {
            case "object" -> valor.isObject();
            case "array" -> valor.isArray();
            case "string" -> valor.isString();
            case "boolean" -> valor.isBoolean();
            case "number" -> valor.isNumber();
            // 2.0 é inteiro em JSON Schema; 2.5 não.
            case "integer" -> valor.isIntegralNumber() || valor.isNumber()
                    && valor.decimalValue().stripTrailingZeros().scale() <= 0;
            case "null" -> valor.isNull();
            default -> true;
        };
    }

    private static String nomeDoTipo(String tipo) {
        return switch (tipo) {
            case "object" -> "um objeto";
            case "array" -> "uma lista";
            case "string" -> "um texto";
            case "boolean" -> "true ou false";
            case "number" -> "um número";
            case "integer" -> "um número inteiro";
            default -> tipo;
        };
    }

    private static boolean contem(JsonNode opcoes, JsonNode valor) {
        for (JsonNode opcao : opcoes) {
            if (igual(valor, opcao)) {
                return true;
            }
        }
        return false;
    }

    /** Número compara pelo valor: o 1 do esquema é o 1.0 que o modelo mandou. */
    private static boolean igual(JsonNode a, JsonNode b) {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue()) == 0;
        }
        return a.equals(b);
    }
}
