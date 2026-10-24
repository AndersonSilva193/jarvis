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
    public static final int MAX_CATEGORIA = 60;
    static final int MAX_URL = 2048;

    /** O layout que não tem itens: o conteúdo todo vai no objeto 'grafico'. */
    public static final String GRAFICO = "grafico";

    /** Os desenhos que a HUD sabe fazer. É o enum que vai no schema da ferramenta. */
    public static final List<String> LAYOUTS =
            List.of("ranking", "ficha", "lista", "linha_do_tempo", "destaque", GRAFICO);

    /** Os desenhos que empilham: é neles que valor negativo não é representável. */
    public static final String BARRAS_EMPILHADAS = "barras_empilhadas";
    public static final String ROSCA = "rosca";

    /** Os quatro desenhos de gráfico. Também vira enum no schema. */
    public static final List<String> TIPOS_GRAFICO =
            List.of("linha", "barras", BARRAS_EMPILHADAS, ROSCA);

    public static final int MAX_SERIES = 3;
    /** Quatro partes é o que o olho separa por cor numa barra; acima disso é um borrão. */
    public static final int MAX_PARTES_EMPILHADAS = 4;
    /** Dois pontos é o mínimo para existir uma linha; 24 é um dia de hora em hora. */
    public static final int MIN_PONTOS_LINHA = 2;
    public static final int MAX_PONTOS_LINHA = 24;
    /** Doze barras é um ano mês a mês; acima disso a tela vira um pente. */
    public static final int MAX_BARRAS = 12;
    /** Duas fatias no mínimo — uma fatia só é sempre 100% e não compara nada — e seis no teto. */
    public static final int MIN_FATIAS_ROSCA = 2;
    public static final int MAX_FATIAS_ROSCA = 6;
    // Públicos porque o gráfico do servidor é conferido contra os mesmos limites da HUD, e o
    // schema das duas ferramentas anuncia os mesmos números ao modelo.
    public static final int MAX_ROTULO_EIXO = 24;
    public static final int MAX_NOME_SERIE = 40;
    public static final int MAX_UNIDADE = 10;

    /** As origens possíveis de um gráfico; o modelo só pode pedir a primeira. */
    static final String DE_PESQUISA = "pesquisa";
    static final String DO_SERVIDOR = "servidor";

    private static final Set<String> ESQUEMAS = Set.of("http", "https");

    private MontadorDeCardDePesquisa() {
    }

    /** A faixa do eixo e o nome do que ele conta, que mudam com o desenho. */
    public record LimiteDoEixo(int minimo, int maximo, String substantivo) {
    }

    /**
     * Quantos rótulos o eixo aceita, por tipo.
     *
     * Está aqui, e público, porque os dois montadores e o {@code grafico_de_dados} precisam
     * exatamente da mesma conta: três cópias do mesmo ternário é onde eles iam divergir.
     */
    public static LimiteDoEixo limiteDoEixo(String tipo) {
        return switch (tipo) {
            case "linha" -> new LimiteDoEixo(MIN_PONTOS_LINHA, MAX_PONTOS_LINHA, "pontos");
            case ROSCA -> new LimiteDoEixo(MIN_FATIAS_ROSCA, MAX_FATIAS_ROSCA, "fatias");
            default -> new LimiteDoEixo(1, MAX_BARRAS, "categorias");
        };
    }

    /** Quantas séries cada desenho aceita: a rosca é uma só, a empilhada vai a quatro. */
    public static int maxSeries(String tipo) {
        return switch (tipo) {
            case ROSCA -> 1;
            case BARRAS_EMPILHADAS -> MAX_PARTES_EMPILHADAS;
            default -> MAX_SERIES;
        };
    }

    /**
     * O que vale só para quem empilha: valor negativo nunca, e soma zero só fora da rosca.
     *
     * Segmentos de sinais trocados não compartilham a mesma base, então o tamanho que o olho mede
     * deixa de ser o total — é o mesmo tipo de mentira que uma série fora de escala. A rosca ainda
     * precisa de soma maior que zero: sem total não há fatia, e um anel vazio não é desenhável.
     * Uma barra empilhada somando zero, ao contrário, é um período legítimo sem movimento.
     */
    static void conferirRegrasDoTipo(String tipo, List<DadosDoCard.Grafico.Serie> series) {
        if (!ROSCA.equals(tipo) && !BARRAS_EMPILHADAS.equals(tipo)) {
            return;
        }
        double soma = 0;
        for (DadosDoCard.Grafico.Serie serie : series) {
            for (int v = 0; v < serie.valores().size(); v++) {
                double valor = serie.valores().get(v);
                if (valor < 0) {
                    throw new IllegalArgumentException("gráfico de " + tipo + ", série '"
                            + serie.nome() + "', valor " + (v + 1) + ": " + valor
                            + " é negativo, e o que empilha precisa de valor zero ou positivo");
                }
                soma += valor;
            }
        }
        if (ROSCA.equals(tipo) && soma <= 0) {
            throw new IllegalArgumentException(
                    "gráfico de rosca precisa de soma maior que zero; as fatias somam " + soma);
        }
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

        List<DadosDoCard.Fonte> fontes = fontes(entrada.path("fontes"));
        // O layout grafico é o único sem itens: o conteúdo inteiro vai no objeto 'grafico'.
        boolean ehGrafico = GRAFICO.equals(layout);
        List<DadosDoCard.ItemDoCard> itens =
                ehGrafico ? List.of() : itens(entrada.path("itens"), layout);
        DadosDoCard.Grafico grafico =
                ehGrafico ? grafico(entrada.path(GRAFICO), referencia, fontes) : null;
        return new DadosDoCard.Pesquisa(categoria, tema, referencia, layout, itens, resumo,
                fontes, consultadoEm, grafico);
    }

    /**
     * O gráfico vindo do modelo, conferido ponto a ponto.
     *
     * Aqui não dá para "quase certo": uma série de tamanho diferente do eixo desenha um gráfico
     * que mente, e um NaN vira uma linha que some no meio. Por isso tudo é exigido antes, e nada
     * é completado por conta própria.
     *
     * A {@code origem} é a trava que separa número pesquisado de número da casa: o modelo só pode
     * mandar "pesquisa", e aí tem de dizer de onde veio (fonte) e de quando é (referência).
     */
    static DadosDoCard.Grafico grafico(JsonNode no, String referencia,
            List<DadosDoCard.Fonte> fontes) {
        if (no == null || !no.isObject()) {
            throw new IllegalArgumentException("mande o objeto 'grafico' com tipo, eixoX e series");
        }
        // O teto é 20, e não 10, porque 'barras_empilhadas' tem 17: com 10 o modelo receberia
        // "'tipo' acima de 10 caracteres" em vez da lista de tipos, que é a única frase que ajuda.
        String tipo = texto(no, "tipo", 20, true);
        if (!TIPOS_GRAFICO.contains(tipo)) {
            throw new IllegalArgumentException(
                    "tipo do gráfico deve ser um de " + TIPOS_GRAFICO + ", não '" + tipo + "'");
        }

        List<String> eixoX = eixoX(no.path("eixoX"), tipo);
        List<DadosDoCard.Grafico.Serie> series = series(no.path("series"), eixoX.size(), tipo);
        conferirRegrasDoTipo(tipo, series);
        String unidade = texto(no, "unidade", MAX_UNIDADE, false);
        DadosDoCard.Grafico.Destaque destaque = destaque(no.path("destaque"), series.size(),
                eixoX.size());

        String origem = texto(no, "origem", 20, false);
        if (origem.isEmpty()) {
            origem = DE_PESQUISA;
        }
        if (DO_SERVIDOR.equals(origem)) {
            throw new IllegalArgumentException(
                    "origem 'servidor' é marcada pelo servidor; use grafico_de_dados");
        }
        if (!DE_PESQUISA.equals(origem)) {
            throw new IllegalArgumentException("origem do gráfico deve ser 'pesquisa'");
        }
        if (fontes.isEmpty()) {
            throw new IllegalArgumentException("gráfico de pesquisa precisa de ao menos uma fonte");
        }
        if (referencia.isBlank()) {
            throw new IllegalArgumentException(
                    "gráfico de pesquisa precisa de 'referencia' com a data da informação");
        }
        return new DadosDoCard.Grafico(tipo, eixoX, series, unidade, destaque, origem);
    }

    /** Os rótulos do eixo, com o teto que muda por tipo: linha é série temporal, barras é lista. */
    private static List<String> eixoX(JsonNode lista, String tipo) {
        if (lista == null || !lista.isArray() || lista.isEmpty()) {
            throw new IllegalArgumentException("mande os rótulos em 'eixoX'");
        }
        LimiteDoEixo limite = limiteDoEixo(tipo);
        if (lista.size() < limite.minimo() || lista.size() > limite.maximo()) {
            throw new IllegalArgumentException("gráfico de " + tipo + " precisa de "
                    + limite.minimo() + " a " + limite.maximo() + " " + limite.substantivo()
                    + " no eixoX; você mandou " + lista.size());
        }
        List<String> eixoX = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            String rotulo = Json.texto(lista.get(i)).trim();
            if (rotulo.isEmpty()) {
                throw new IllegalArgumentException("eixoX " + (i + 1) + ": rótulo vazio");
            }
            if (rotulo.length() > MAX_ROTULO_EIXO) {
                throw new IllegalArgumentException("eixoX " + (i + 1) + ": rótulo acima de "
                        + MAX_ROTULO_EIXO + " caracteres");
            }
            eixoX.add(rotulo);
        }
        return eixoX;
    }

    /** As séries, todas do tamanho do eixo e só com número que existe de verdade. */
    private static List<DadosDoCard.Grafico.Serie> series(JsonNode lista, int pontos, String tipo) {
        int max = maxSeries(tipo);
        if (lista == null || !lista.isArray() || lista.isEmpty()) {
            throw new IllegalArgumentException("mande de 1 a " + max + " séries em 'series'");
        }
        if (lista.size() > max) {
            throw new IllegalArgumentException(ROSCA.equals(tipo)
                    ? "a rosca aceita uma série só; você mandou " + lista.size()
                    : "no máximo " + max + " séries; você mandou " + lista.size());
        }
        List<DadosDoCard.Grafico.Serie> series = new ArrayList<>(lista.size());
        for (int i = 0; i < lista.size(); i++) {
            JsonNode serie = lista.get(i);
            if (serie == null || !serie.isObject()) {
                throw new IllegalArgumentException("série " + (i + 1) + ": mande um objeto");
            }
            String nome = exigir(serie, "nome", MAX_NOME_SERIE, i, "série");
            JsonNode valores = serie.path("valores");
            if (!valores.isArray() || valores.size() != pontos) {
                throw new IllegalArgumentException("série '" + nome + "': "
                        + (valores.isArray() ? valores.size() : 0) + " valores para " + pontos
                        + " pontos do eixoX");
            }
            List<Double> numeros = new ArrayList<>(pontos);
            for (int v = 0; v < pontos; v++) {
                Double valor = numero(valores.get(v));
                if (valor == null || !Double.isFinite(valor)) {
                    throw new IllegalArgumentException("série '" + nome + "', valor " + (v + 1)
                            + ": tem de ser número finito");
                }
                numeros.add(valor);
            }
            series.add(new DadosDoCard.Grafico.Serie(nome, numeros));
        }
        return series;
    }

    /** O ponto realçado, ou null. Índice fora da faixa é recusado: a HUD não pode ler o vazio. */
    private static DadosDoCard.Grafico.Destaque destaque(JsonNode no, int series, int pontos) {
        if (no == null || !no.isObject()) {
            return null;
        }
        int serie = (int) Json.numero(no.path("serie"));
        int indice = (int) Json.numero(no.path("indice"));
        if (serie < 0 || serie >= series) {
            throw new IllegalArgumentException("destaque: série " + serie + " não existe");
        }
        if (indice < 0 || indice >= pontos) {
            throw new IllegalArgumentException("destaque: ponto " + indice + " não existe");
        }
        return new DadosDoCard.Grafico.Destaque(serie, indice);
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
        return exigir(item, campo, max, indice, "item");
    }

    /** O mesmo, com o nome do que está sendo numerado — "item 2:", "série 2:". */
    private static String exigir(JsonNode item, String campo, int max, int indice, String oQue) {
        try {
            return texto(item, campo, max, true);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(oQue + " " + (indice + 1) + ": " + e.getMessage());
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
