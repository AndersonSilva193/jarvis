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
            if (raiz.isObject()) {
                String paginado = paginarTexto((ObjectNode) raiz, maximo, json);
                if (paginado != null) return paginado;
                paginado = paginarArquivos((ObjectNode) raiz, maximo, json);
                if (paginado != null) return paginado;
                paginado = paginarLeituraEstruturada((ObjectNode) raiz, maximo, json);
                if (paginado != null) return paginado;
            }
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

    /** O próximo deslocamento deve apontar para o fim do texto visto pelo modelo. */
    private static String paginarTexto(ObjectNode raiz, int maximo, JsonMapper json) {
        String formato = raiz.path("formato").asText("");
        if (!formato.equals("txt") && !formato.equals("md") && !formato.equals("docx")) return null;
        JsonNode conteudoNo = raiz.path("conteudo");
        JsonNode inicioNo = raiz.path("inicio");
        JsonNode totalNo = raiz.path("totalCaracteres");
        if (!conteudoNo.isString() || !inicioNo.isNumber() || !totalNo.isNumber()) return null;
        String conteudo = conteudoNo.stringValue();
        int inicio = inicioNo.intValue();
        int total = totalNo.intValue();
        if (inicio < 0 || total < inicio || conteudo.length() > total - inicio) return null;
        JsonNode proximo = raiz.path("proximoInicio");
        if (!proximo.isMissingNode() && (!proximo.isNumber()
                || proximo.intValue() != inicio + conteudo.length())) return null;

        ObjectNode curto = raiz.deepCopy();
        curto.put("resultadoCortado", true);
        if (raiz.path("aviso").isString())
            curto.put("avisoDeConteudo", raiz.path("aviso").stringValue());
        curto.put("aviso", AVISO);
        curto.put("truncado", true);
        int baixo = 0, alto = conteudo.length();
        while (baixo < alto) {
            int meio = baixo + (alto - baixo + 1) / 2;
            curto.put("conteudo", conteudo.substring(0, meio));
            curto.put("proximoInicio", inicio + meio);
            if (curto.toString().length() <= maximo) baixo = meio;
            else alto = meio - 1;
        }
        int visivel = baixo;
        if (visivel > 0 && visivel < conteudo.length()
                && Character.isHighSurrogate(conteudo.charAt(visivel - 1))) visivel--;
        if (visivel == 0) {
            ObjectNode aviso = json.createObjectNode();
            for (String chave : new String[] {"id", "nome", "formato", "versaoEsperada", "inicio"}) {
                JsonNode valor = raiz.path(chave);
                if (!valor.isMissingNode()) aviso.set(chave, valor);
            }
            aviso.put("resultadoCortado", true).put("aviso", AVISO);
            aviso.put("instrucao", "Repita esta leitura com maximoCaracteres menor.");
            return aviso.toString();
        }
        curto.put("conteudo", conteudo.substring(0, visivel));
        curto.put("proximoInicio", inicio + visivel);
        return curto.toString();
    }

    /** Recalcula página e tamanho para que a próxima consulta comece após o último item visível. */
    private static String paginarArquivos(ObjectNode raiz, int maximo, JsonMapper json) {
        JsonNode itens = raiz.path("arquivos");
        JsonNode paginaNo = raiz.path("pagina");
        JsonNode tamanhoNo = raiz.path("tamanhoPagina");
        JsonNode totalNo = raiz.path("total");
        if (!itens.isArray() || !paginaNo.isNumber() || !tamanhoNo.isNumber()
                || !totalNo.isNumber()) return null;
        int pagina = paginaNo.intValue(), tamanho = tamanhoNo.intValue(), total = totalNo.intValue();
        if (pagina < 1 || tamanho < 1 || total < 0) return null;
        long inicio = (long) (pagina - 1) * tamanho;
        ObjectNode curto = raiz.deepCopy();
        curto.put("resultadoCortado", true);
        curto.put("aviso", AVISO);
        for (int visiveis = Math.min(itens.size(), tamanho); visiveis >= 1; visiveis--) {
            if (tamanho % visiveis != 0) continue;
            ArrayNode lista = json.createArrayNode();
            for (int i = 0; i < visiveis; i++) lista.add(itens.get(i));
            curto.set("arquivos", lista);
            curto.put("pagina", inicio / visiveis + 1);
            curto.put("tamanhoPagina", visiveis);
            curto.put("temMais", inicio + visiveis < total);
            if (curto.toString().length() <= maximo) return curto.toString();
        }
        ObjectNode aviso = json.createObjectNode();
        aviso.put("total", total).put("resultadoCortado", true).put("aviso", AVISO);
        aviso.put("instrucao", "Refine o nome e consulte listar_arquivos novamente desde a pagina 1.");
        return aviso.toString();
    }

    /** A leitura do PC tem formatos e cursores distintos; o resumo genérico não pode alterá-los. */
    private static String paginarLeituraEstruturada(ObjectNode raiz, int maximo, JsonMapper json) {
        String formato = raiz.path("formato").asText("");
        return switch (formato) {
            case "json" -> resumirValorJson(raiz, maximo, json);
            case "xlsx" -> {
                String fragmento = paginarFragmento(raiz, "fragmentoCelula",
                        "proximoInicioCelula", maximo, json);
                if (fragmento != null) yield fragmento;
                if (raiz.path("correspondencias").isArray())
                    yield resumirItensDeLeitura(raiz, "correspondencias", maximo, json);
                yield resumirItensDeLeitura(raiz, "celulas", maximo, json);
            }
            case "csv" -> {
                String fragmento = paginarFragmento(raiz, "fragmentoLinha",
                        "proximoInicioLinha", maximo, json);
                yield fragmento != null ? fragmento
                        : resumirItensDeLeitura(raiz, "linhas", maximo, json);
            }
            case "pdf" -> paginarPdf(raiz, maximo, json);
            default -> null;
        };
    }

    /** Nunca inventa um valorAnterior parcial: a edição JSON exige igualdade exata. */
    private static String resumirValorJson(ObjectNode raiz, int maximo, JsonMapper json) {
        if (!raiz.has("valor")) return null;
        ObjectNode curto = leituraCortada(raiz);
        curto.remove("valor");
        curto.put("truncado", true);
        curto.put("instrucao", "Releia com ponteiro JSON mais específico. Se o valor escalar inteiro "
                + "não couber, não use um valorAnterior parcial para editar.");
        return caberOuAvisar(curto, raiz, maximo, json);
    }

    /** Fragmentos grandes de célula e linha têm deslocamento dentro do JSON serializado. */
    private static String paginarFragmento(ObjectNode raiz, String campo, String cursor,
            int maximo, JsonMapper json) {
        JsonNode fragmento = raiz.path(campo);
        if (!fragmento.isObject() || !fragmento.path("texto").isString()
                || !fragmento.path("inicio").isNumber()) return null;
        int inicio = fragmento.path("inicio").intValue();
        String texto = fragmento.path("texto").stringValue();
        if (inicio < 0 || inicio > Integer.MAX_VALUE - texto.length()) return null;
        ObjectNode curto = leituraCortada(raiz);
        curto.put("truncado", true);
        ObjectNode destino = (ObjectNode) curto.path(campo);
        int baixo = 0, alto = texto.length();
        while (baixo < alto) {
            int meio = baixo + (alto - baixo + 1) / 2;
            destino.put("texto", texto.substring(0, meio));
            curto.put(cursor, inicio + meio);
            if (curto.toString().length() <= maximo) baixo = meio;
            else alto = meio - 1;
        }
        int visivel = baixo;
        if (visivel > 0 && visivel < texto.length()
                && Character.isHighSurrogate(texto.charAt(visivel - 1))) visivel--;
        if (visivel == 0) return avisoDeLeitura(raiz, maximo, json,
                "Releia a célula ou linha com um intervalo menor; o fragmento não coube.");
        destino.put("texto", texto.substring(0, visivel));
        curto.put(cursor, inicio + visivel);
        return curto.toString();
    }

    /** Mantém páginas completas; se necessário, corta apenas o texto da última página visível. */
    private static String paginarPdf(ObjectNode raiz, int maximo, JsonMapper json) {
        JsonNode paginas = raiz.path("paginas");
        if (!paginas.isArray()) return null;
        for (int quantidade = paginas.size(); quantidade >= 1; quantidade--) {
            ObjectNode curto = leituraCortada(raiz);
            ArrayNode visiveis = (ArrayNode) curto.path("paginas");
            while (visiveis.size() > quantidade) visiveis.remove(visiveis.size() - 1);
            if (quantidade < paginas.size()) {
                JsonNode omitida = paginas.get(quantidade);
                curto.put("truncado", true);
                curto.put("continuarPagina", omitida.path("pagina").intValue());
                curto.put("proximoInicio", omitida.path("inicio").intValue());
            }
            if (curto.toString().length() <= maximo) return curto.toString();
            if (quantidade != 1) continue;
            JsonNode primeira = paginas.get(0);
            if (!primeira.path("texto").isString() || !primeira.path("pagina").isNumber()
                    || !primeira.path("inicio").isNumber()) break;
            String texto = primeira.path("texto").stringValue();
            int inicio = primeira.path("inicio").intValue();
            ObjectNode pagina = (ObjectNode) visiveis.get(0);
            curto.put("truncado", true);
            curto.put("continuarPagina", primeira.path("pagina").intValue());
            int baixo = 0, alto = texto.length();
            while (baixo < alto) {
                int meio = baixo + (alto - baixo + 1) / 2;
                pagina.put("texto", texto.substring(0, meio));
                curto.put("proximoInicio", inicio + meio);
                if (curto.toString().length() <= maximo) baixo = meio;
                else alto = meio - 1;
            }
            int visivel = baixo;
            if (visivel > 0 && visivel < texto.length()
                    && Character.isHighSurrogate(texto.charAt(visivel - 1))) visivel--;
            if (visivel == 0) break;
            pagina.put("texto", texto.substring(0, visivel));
            curto.put("proximoInicio", inicio + visivel);
            return curto.toString();
        }
        return avisoDeLeitura(raiz, maximo, json,
                "Releia PDF com uma página por vez e início da página solicitado.");
    }

    /** XLSX e CSV só expõem itens inteiros; não preserva cursores do agente após omitir itens. */
    private static String resumirItensDeLeitura(ObjectNode raiz, String campo,
            int maximo, JsonMapper json) {
        JsonNode itens = raiz.path(campo);
        if (!itens.isArray()) return avisoDeLeitura(raiz, maximo, json,
                "Releia uma região menor do arquivo.");
        for (int quantidade = itens.size(); quantidade >= 0; quantidade--) {
            ObjectNode curto = leituraCortada(raiz);
            ArrayNode visiveis = (ArrayNode) curto.path(campo);
            while (visiveis.size() > quantidade) visiveis.remove(visiveis.size() - 1);
            if (quantidade < itens.size()) {
                curto.put("truncado", true);
                curto.remove("continuarLinha");
                curto.remove("continuarColuna");
                curto.remove("proximoInicioCelula");
                curto.remove("proximoInicioLinha");
                String instrucao = switch (campo) {
                    case "celulas" -> "Releia a mesma aba em intervalo menor a partir da célula "
                            + itens.get(quantidade).path("endereco").asText("")
                            + "; use linhaInicial/colunaInicial e confira tipoAnterior/valorAnterior.";
                    case "correspondencias" -> "Busca XLSX incompleta: refine buscaValor com aba, "
                            + "buscaCabecalho ou tabela antes de escolher uma célula para editar.";
                    default -> "Releia CSV com quantidadeLinhas menor desde a mesma linhaInicial; "
                            + "depois avance pelo número de linhas visíveis.";
                };
                curto.put("instrucao", instrucao);
                if (campo.equals("linhas")) curto.put("linhasVisiveis", quantidade);
            }
            if (campo.equals("celulas") && quantidade == 0 && !itens.isEmpty()) {
                String fragmento = fragmentarPrimeiraCelula(raiz, itens.get(0), maximo, json);
                if (fragmento != null) return fragmento;
            }
            if (curto.toString().length() <= maximo) return curto.toString();
        }
        return avisoDeLeitura(raiz, maximo, json,
                "Releia uma região menor do arquivo; nenhum item completo coube.");
    }

    /** O agente aceita inicioCelula>0; o primeiro pedaço pode nascer aqui sem alterar o valor. */
    private static String fragmentarPrimeiraCelula(ObjectNode raiz, JsonNode celula,
            int maximo, JsonMapper json) {
        String endereco = celula.path("endereco").asText("");
        int corte = 0;
        while (corte < endereco.length() && endereco.charAt(corte) >= 'A'
                && endereco.charAt(corte) <= 'Z') corte++;
        if (corte < 1 || corte > 3 || corte == endereco.length()) return null;
        int coluna = 0;
        for (int i = 0; i < corte; i++) coluna = coluna * 26 + endereco.charAt(i) - 'A' + 1;
        int linha;
        try { linha = Integer.parseInt(endereco.substring(corte)); }
        catch (NumberFormatException invalido) { return null; }
        if (linha < 1 || linha > 1_048_576 || coluna > 16_384
                || !endereco.substring(corte).equals(Integer.toString(linha))) return null;

        String completa = celulaCompletaResumida(raiz, celula, maximo, json);
        if (completa != null) return completa;
        String texto = celula.toString();
        ObjectNode curto = leituraCortada(raiz);
        curto.set("celulas", json.createArrayNode());
        curto.put("truncado", true);
        curto.put("continuarLinha", linha);
        curto.put("continuarColuna", coluna);
        curto.put("instrucao", "Continue a mesma célula com inicioCelula=proximoInicioCelula "
                + "e intervalo de uma célula; não edite com valorAnterior parcial.");
        ObjectNode fragmento = curto.putObject("fragmentoCelula");
        fragmento.put("endereco", endereco).put("inicio", 0)
                .put("totalCaracteres", texto.length());
        int baixo = 0, alto = texto.length();
        while (baixo < alto) {
            int meio = baixo + (alto - baixo + 1) / 2;
            fragmento.put("texto", texto.substring(0, meio));
            curto.put("proximoInicioCelula", meio);
            if (curto.toString().length() <= maximo) baixo = meio;
            else alto = meio - 1;
        }
        int visivel = baixo;
        if (visivel > 0 && visivel < texto.length()
                && Character.isHighSurrogate(texto.charAt(visivel - 1))) visivel--;
        if (visivel == 0) return null;
        if (visivel == texto.length()) return null;
        fragmento.put("texto", texto.substring(0, visivel));
        curto.put("proximoInicioCelula", visivel);
        return curto.toString();
    }

    /** Se o item inteiro couber sem os metadados grandes, entrega seu valor exato e nenhum cursor. */
    private static String celulaCompletaResumida(ObjectNode raiz, JsonNode celula,
            int maximo, JsonMapper json) {
        ObjectNode curto = json.createObjectNode();
        for (String chave : new String[] {"id", "nome", "formato", "versaoEsperada"}) {
            JsonNode valor = raiz.path(chave);
            if (!valor.isMissingNode()) curto.set(chave, valor);
        }
        if (raiz.path("aviso").isString())
            curto.put("avisoDeConteudo", raiz.path("aviso").stringValue());
        curto.putArray("celulas").add(celula);
        curto.put("resultadoCortado", true).put("truncado", true).put("aviso", AVISO);
        JsonNode itens = raiz.path("celulas");
        String primeiraOmitida = itens.size() > 1 ? itens.get(1).path("endereco").asText("") : "";
        curto.put("instrucao", primeiraOmitida.isEmpty()
                ? "Celula completa; releia o intervalo se precisar dos demais dados omitidos."
                : "Celula completa; releia intervalo menor a partir de " + primeiraOmitida
                        + " para as demais celulas.");
        return curto.toString().length() <= maximo ? curto.toString() : null;
    }

    private static ObjectNode leituraCortada(ObjectNode raiz) {
        ObjectNode curto = raiz.deepCopy();
        if (raiz.path("aviso").isString())
            curto.put("avisoDeConteudo", raiz.path("aviso").stringValue());
        curto.put("resultadoCortado", true);
        curto.put("aviso", AVISO);
        return curto;
    }

    private static String avisoDeLeitura(ObjectNode raiz, int maximo, JsonMapper json,
            String instrucao) {
        ObjectNode aviso = json.createObjectNode();
        for (String chave : new String[] {"id", "nome", "formato", "versaoEsperada", "ponteiro"}) {
            JsonNode valor = raiz.path(chave);
            if (!valor.isMissingNode()) aviso.set(chave, valor);
        }
        aviso.put("resultadoCortado", true).put("aviso", AVISO).put("truncado", true);
        aviso.put("instrucao", instrucao);
        return caberOuAvisar(aviso, raiz, maximo, json);
    }

    private static String caberOuAvisar(ObjectNode curto, ObjectNode raiz,
            int maximo, JsonMapper json) {
        if (curto.toString().length() <= maximo) return curto.toString();
        ObjectNode minimo = json.createObjectNode();
        for (String chave : new String[] {"id", "formato", "versaoEsperada", "ponteiro"}) {
            JsonNode valor = raiz.path(chave);
            if (!valor.isMissingNode()) minimo.set(chave, valor);
        }
        minimo.put("resultadoCortado", true).put("aviso", AVISO);
        minimo.put("instrucao", "Releia com intervalo menor; nenhum valor completo coube.");
        if (minimo.toString().length() <= maximo) return minimo.toString();
        minimo.remove("versaoEsperada");
        minimo.remove("ponteiro");
        return minimo.toString();
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
