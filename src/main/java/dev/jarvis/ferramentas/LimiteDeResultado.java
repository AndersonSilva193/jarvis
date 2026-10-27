package dev.jarvis.ferramentas;

import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Reduz só o texto entregue ao modelo; o resultado estruturado original continua intacto. */
final class LimiteDeResultado {

    static final String AVISO = "[cortado; peça a próxima parte]";

    private LimiteDeResultado() { }

    static String paraModelo(String texto, int maximo, JsonMapper json) {
        if (texto.length() <= maximo) return texto;
        try {
            JsonNode raiz = json.readTree(texto);
            if (raiz.isObject() || raiz.isArray() || raiz.isString()) {
                ObjectNode reduzido;
                if (raiz.isObject()) {
                    reduzido = (ObjectNode) raiz.deepCopy();
                } else if (raiz.isArray()) {
                    reduzido = json.createObjectNode();
                    reduzido.set("itens", raiz.deepCopy());
                } else {
                    reduzido = json.createObjectNode();
                    reduzido.put("valor", raiz.stringValue());
                }
                reduzido.put("resultadoCortado", true);
                reduzido.put("aviso", AVISO);
                while (reduzido.toString().length() > maximo) {
                    ArrayNode lista = maiorLista(reduzido);
                    if (lista != null && lista.size() > 1) {
                        lista.remove(lista.size() - 1);
                        continue;
                    }
                    StringAlvo longo = maiorTexto(reduzido, null);
                    if (longo != null) {
                        int tamanho = Math.max(40, longo.texto.length() / 2);
                        String parte = prefixo(longo.texto, Math.min(tamanho, longo.texto.length() - 1));
                        longo.trocar(parte + "…");
                        continue;
                    }
                    if (lista != null) {
                        lista.remove(lista.size() - 1);
                        continue;
                    }
                    if (!removerMaiorCampo(reduzido)) break;
                }
                if (reduzido.toString().length() <= maximo) return reduzido.toString();
                return minimo(raiz, json, maximo);
            }
        } catch (RuntimeException naoEraJson) {
            // Resultados em prosa continuam em prosa.
        }
        return prefixo(texto, maximo - AVISO.length()) + AVISO;
    }

    private static String minimo(JsonNode raiz, JsonMapper json, int maximo) {
        ObjectNode curto = json.createObjectNode();
        if (raiz.isObject()) {
            for (Map.Entry<String, JsonNode> campo : raiz.properties()) {
                if (!prioritario(campo.getKey()) || !campo.getValue().isValueNode()) continue;
                curto.set(campo.getKey(), campo.getValue());
                if (curto.toString().length() > maximo - 120) curto.remove(campo.getKey());
            }
        }
        curto.put("resultadoCortado", true);
        curto.put("aviso", AVISO);
        curto.put("instrucao", "Repita a leitura com um intervalo menor ou peça a próxima parte.");
        return curto.toString();
    }

    private static boolean prioritario(String chave) {
        String nome = chave.toLowerCase(java.util.Locale.ROOT);
        return nome.equals("id") || nome.equals("nome") || nome.equals("versaoesperada")
                || nome.contains("proximo") || nome.contains("inicio") || nome.contains("pagina")
                || nome.equals("truncado") || nome.equals("resultadocortado") || nome.equals("aviso");
    }

    private static ArrayNode maiorLista(ObjectNode raiz) {
        ArrayNode maior = null;
        for (JsonNode filho : raiz.values()) {
            if (filho.isArray()) {
                ArrayNode lista = (ArrayNode) filho;
                if (!lista.isEmpty() && (maior == null || lista.toString().length() > maior.toString().length()))
                    maior = lista;
            }
        }
        return maior;
    }

    private static StringAlvo maiorTexto(JsonNode no, StringAlvo maior) {
        if (no.isObject()) {
            for (Map.Entry<String, JsonNode> campo : no.properties()) {
                JsonNode filho = campo.getValue();
                if (filho.isString() && !prioritario(campo.getKey())
                        && filho.stringValue().length() > 80
                        && (maior == null || filho.stringValue().length() > maior.texto.length())) {
                    maior = new StringAlvo((ObjectNode) no, campo.getKey(), filho.stringValue());
                }
                maior = maiorTexto(filho, maior);
            }
        }
        return maior;
    }

    private static boolean removerMaiorCampo(ObjectNode raiz) {
        String nome = null;
        int tamanho = 0;
        for (Map.Entry<String, JsonNode> campo : raiz.properties()) {
            if (prioritario(campo.getKey())) continue;
            int atual = campo.getValue().toString().length();
            if (atual > tamanho) {
                nome = campo.getKey();
                tamanho = atual;
            }
        }
        if (nome == null) return false;
        raiz.remove(nome);
        return true;
    }

    private static String prefixo(String texto, int tamanho) {
        int fim = Math.min(texto.length(), Math.max(0, tamanho));
        if (fim > 0 && fim < texto.length() && Character.isHighSurrogate(texto.charAt(fim - 1))) fim--;
        return texto.substring(0, fim);
    }

    private record StringAlvo(ObjectNode pai, String chave, String texto) {
        void trocar(String novo) { pai.put(chave, novo); }
    }
}
