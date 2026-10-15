package dev.jarvis.tela;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/**
 * Transforma o JSON que o modelo manda no {@code mostrar_na_tela} em um card validado.
 *
 * Tudo que vem do modelo é conferido aqui: tamanho de texto, quantidade de itens, faixa dos
 * percentuais e esquema das URLs. Falha vira {@link IllegalArgumentException} com uma frase curta
 * dizendo o que corrigir — o registro de ferramentas devolve isso como {@code is_error}, e o
 * modelo costuma acertar na segunda tentativa.
 *
 * O {@code consultadoEm} é parâmetro, nunca campo de entrada: informação de "quando" inventada
 * pelo modelo é a pior de todas, e é justamente a que a tela mostra em letras pequenas.
 *
 * Estático e sem Spring de propósito, no mesmo molde de {@code NoticiasService.interpretar}:
 * assim o teste confere a validação chamando a função, sem subir contexto.
 */
public final class MontadorDeCardDePesquisa {

    /** Teto de itens. Público porque o schema da ferramenta anuncia o mesmo número. */
    public static final int MAX_ITENS = 8;
    static final int MAX_TITULO = 120;
    static final int MAX_TEXTO = 180;
    static final int MAX_RESUMO = 600;
    public static final int MAX_FONTES = 4;
    static final int MAX_REFERENCIA = 40;
    static final int MAX_CATEGORIA = 60;
    static final int MAX_URL = 2048;

    /** Os desenhos que a HUD sabe fazer. É o enum que vai no schema da ferramenta. */
    public static final List<String> LAYOUTS =
            List.of("ranking", "ficha", "lista", "linha_do_tempo", "destaque");

    private static final Set<String> ESQUEMAS = Set.of("http", "https");

    private MontadorDeCardDePesquisa() {
    }

    /**
     * O card pronto para publicar, com id sorteado e o relógio do servidor.
     *
     * O título do card é o {@code titulo} da entrada — a conclusão, que é o que a janela mostra
     * no alto e o que o modelo vai repetir ao falar do card depois. O assunto fica em
     * {@code dados.tema()}, de onde a HUD tira o subtítulo.
     */
    public static CardTela montar(JsonNode entrada, String origem, String consultadoEm) {
        DadosDoCard.Pesquisa dados = validar(entrada, consultadoEm);
        return CardTela.agora(CardTela.PESQUISA, texto(entrada, "titulo", MAX_TITULO, true),
                dados, origem);
    }

    static DadosDoCard.Pesquisa validar(JsonNode entrada, String consultadoEm) {
        if (entrada == null || !entrada.isObject()) {
            throw new IllegalArgumentException("mande um objeto com categoria, tema, titulo, layout, itens e resumo");
        }
        String categoria = texto(entrada, "categoria", MAX_CATEGORIA, true);
        String tema = texto(entrada, "tema", MAX_CATEGORIA, true);
        // Validado aqui, guardado no CardTela: o titulo é a conclusão e é ela que nomeia a janela.
        texto(entrada, "titulo", MAX_TITULO, true);
        String referencia = texto(entrada, "referencia", MAX_REFERENCIA, false);
        String resumo = texto(entrada, "resumo", MAX_RESUMO, true);

        String layout = texto(entrada, "layout", 20, true);
        if (!LAYOUTS.contains(layout)) {
            throw new IllegalArgumentException("layout deve ser um de " + LAYOUTS + ", não '" + layout + "'");
        }

        List<DadosDoCard.ItemDoCard> itens = itens(entrada.path("itens"), layout);
        List<DadosDoCard.Fonte> fontes = fontes(entrada.path("fontes"));
        return new DadosDoCard.Pesquisa(categoria, tema, referencia, layout, itens, resumo,
                fontes, consultadoEm);
    }

    static List<DadosDoCard.ItemDoCard> itens(JsonNode lista, String layout) {
        if (lista == null || !lista.isArray() || lista.isEmpty()) {
            throw new IllegalArgumentException("mande de 1 a " + MAX_ITENS + " itens em 'itens'");
        }
        if (lista.size() > MAX_ITENS) {
            throw new IllegalArgumentException("no máximo " + MAX_ITENS + " itens; você mandou " + lista.size());
        }
        List<DadosDoCard.ItemDoCard> itens = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            JsonNode item = lista.get(i);
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("item " + (i + 1) + ": mande um objeto");
            }
            itens.add(switch (layout) {
                case "ranking" -> deRanking(item, i);
                case "ficha" -> new DadosDoCard.ItemDoCard.Campo(
                        exigir(item, "rotulo", MAX_TEXTO, i), exigir(item, "valor", MAX_TEXTO, i));
                case "linha_do_tempo" -> new DadosDoCard.ItemDoCard.Marco(
                        exigir(item, "data", MAX_REFERENCIA, i), exigir(item, "texto", MAX_TEXTO, i));
                case "destaque" -> new DadosDoCard.ItemDoCard.Numero(
                        exigir(item, "valor", MAX_TEXTO, i), exigir(item, "legenda", MAX_TEXTO, i));
                default -> new DadosDoCard.ItemDoCard.Linha(exigir(item, "texto", MAX_TEXTO, i));
            });
        }
        return itens;
    }

    private static DadosDoCard.ItemDoCard.Posicao deRanking(JsonNode item, int indice) {
        String nome = exigir(item, "nome", MAX_TEXTO, indice);
        String unidade = texto(item, "unidade", 12, false);
        Double valor = percentual(item, unidade, indice);
        long posicao = Json.numero(item.path("posicao"));
        return new DadosDoCard.ItemDoCard.Posicao(
                posicao > 0 ? (int) posicao : indice + 1,
                nome,
                texto(item, "detalhe", MAX_TEXTO, false),
                valor,
                unidade,
                texto(item, "etiqueta", 40, false));
    }

    /**
     * O valor numérico de um item de ranking, com a trava dos percentuais: unidade "%" fora de
     * 0 a 100 é erro de leitura da fonte, e na tela viraria uma barra estourando o card.
     */
    static Double percentual(JsonNode item, String unidade, int indice) {
        JsonNode no = item.path("valor");
        if (no.isMissingNode() || no.isNull()) {
            return null;
        }
        Double valor = numero(no);
        if (valor == null) {
            throw new IllegalArgumentException(
                    "item " + (indice + 1) + ": 'valor' tem de ser número, não '" + no.asString() + "'");
        }
        if ("%".equals(unidade.trim()) && (valor < 0 || valor > 100)) {
            throw new IllegalArgumentException(
                    "item " + (indice + 1) + ": valor " + valor + " fora de 0-100 para unidade '%'");
        }
        return valor;
    }

    static List<DadosDoCard.Fonte> fontes(JsonNode lista) {
        if (lista == null || !lista.isArray() || lista.isEmpty()) {
            return List.of();
        }
        if (lista.size() > MAX_FONTES) {
            throw new IllegalArgumentException("no máximo " + MAX_FONTES + " fontes");
        }
        List<DadosDoCard.Fonte> fontes = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            JsonNode fonte = lista.get(i);
            String nome = Json.texto(fonte.path("nome")).trim();
            String url = urlSegura(Json.texto(fonte.path("url")).trim());
            if (nome.isEmpty()) {
                throw new IllegalArgumentException("fonte " + (i + 1) + ": falta o nome");
            }
            if (nome.length() > MAX_CATEGORIA) {
                throw new IllegalArgumentException(
                        "fonte " + (i + 1) + ": nome acima de " + MAX_CATEGORIA + " caracteres");
            }
            fontes.add(new DadosDoCard.Fonte(nome, url));
        }
        return fontes;
    }

    /**
     * A URL de uma fonte: só http e https, sem credencial embutida.
     *
     * Esta URL vai para a tela como link clicável, então um "javascript:" aqui seria script
     * rodando na HUD. Vazia é aceita — fonte sem link acontece.
     */
    static String urlSegura(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        if (url.length() > MAX_URL) {
            throw new IllegalArgumentException("URL de fonte longa demais");
        }
        try {
            java.net.URI uri = new java.net.URI(url);
            String esquema = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!ESQUEMAS.contains(esquema)) {
                throw new IllegalArgumentException("URL de fonte precisa ser http ou https: " + url);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("URL de fonte sem host: " + url);
            }
            if (uri.getUserInfo() != null) {
                throw new IllegalArgumentException("URL de fonte com usuário e senha embutidos");
            }
            return uri.toASCIIString();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("URL de fonte inválida: " + url);
        }
    }

    /** Um campo de texto do objeto, com o teto de tamanho e a checagem de obrigatório. */
    static String texto(JsonNode no, String campo, int max, boolean obrigatorio) {
        String valor = Json.texto(no.path(campo)).trim();
        if (valor.isEmpty()) {
            if (obrigatorio) {
                throw new IllegalArgumentException("falta o campo '" + campo + "'");
            }
            return "";
        }
        if (valor.length() > max) {
            throw new IllegalArgumentException(
                    "'" + campo + "' acima de " + max + " caracteres (" + valor.length() + ")");
        }
        return valor;
    }

    /** O mesmo, mas dentro de um item, para a mensagem dizer qual item está errado. */
    private static String exigir(JsonNode item, String campo, int max, int indice) {
        try {
            return texto(item, campo, max, true);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("item " + (indice + 1) + ": " + e.getMessage());
        }
    }

    /** Número vindo como número ou como texto ("12,5" inclusive, que é o que o modelo escreve em pt). */
    private static Double numero(JsonNode no) {
        if (no.isNumber()) {
            return no.doubleValue();
        }
        if (!no.isString()) {
            return null;
        }
        try {
            return Double.valueOf(no.stringValue().trim().replace("%", "").replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
