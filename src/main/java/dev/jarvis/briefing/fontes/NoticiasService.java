package dev.jarvis.briefing.fontes;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import dev.jarvis.config.JarvisProperties;

/**
 * Radar de notícias: baixa os feeds configurados em NOTICIAS_FEEDS e devolve as manchetes
 * das últimas 24 horas. Entende RSS 2.0 e Atom, usando só a API de XML do próprio Java.
 *
 * Segurança: o parser é criado com DTD e entidades externas desligadas (proteção contra XXE),
 * porque feed é conteúdo de terceiro e não temos controle do que vem dentro.
 */
@Service
public class NoticiasService {

    private static final Logger log = LoggerFactory.getLogger(NoticiasService.class);

    private static final Duration VALIDADE_CACHE = Duration.ofMinutes(30);
    /** Depois de uma falha, esperamos pouco antes de tentar de novo (mas não tentamos a cada pergunta). */
    private static final Duration VALIDADE_FALHA = Duration.ofMinutes(5);
    private static final Duration JANELA = Duration.ofHours(24);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Teto para o XML baixado: feed é conteúdo de terceiro e a VPS tem memória limitada. */
    private static final int LIMITE_BYTES = 4 * 1024 * 1024;
    private static final int TAMANHO_RESUMO = 300;
    /** Tema especial que varre todos os feeds de uma vez. */
    public static final String TODOS = "todos";

    private static final Pattern TAG_IMG = Pattern.compile("<img[^>]+src\\s*=\\s*[\"']([^\"']+)[\"']",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS_HTML = Pattern.compile("<[^>]*>");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");
    private static final Pattern ENTIDADE = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]*);");

    /** Entidades HTML nomeadas que aparecem de verdade nos feeds (as numéricas resolvem sozinhas). */
    private static final Map<String, String> NOMEADAS = Map.ofEntries(
            Map.entry("amp", "&"), Map.entry("lt", "<"), Map.entry("gt", ">"),
            Map.entry("quot", "\""), Map.entry("apos", "'"), Map.entry("nbsp", " "),
            Map.entry("aacute", "á"), Map.entry("agrave", "à"), Map.entry("acirc", "â"),
            Map.entry("atilde", "ã"), Map.entry("auml", "ä"), Map.entry("ccedil", "ç"),
            Map.entry("eacute", "é"), Map.entry("egrave", "è"), Map.entry("ecirc", "ê"),
            Map.entry("iacute", "í"), Map.entry("icirc", "î"), Map.entry("ntilde", "ñ"),
            Map.entry("oacute", "ó"), Map.entry("ocirc", "ô"), Map.entry("otilde", "õ"),
            Map.entry("ouml", "ö"), Map.entry("uacute", "ú"), Map.entry("ucirc", "û"),
            Map.entry("uuml", "ü"), Map.entry("hellip", "…"), Map.entry("mdash", "—"),
            Map.entry("ndash", "–"), Map.entry("lsquo", "'"), Map.entry("rsquo", "'"),
            Map.entry("ldquo", "\""), Map.entry("rdquo", "\""), Map.entry("laquo", "«"),
            Map.entry("raquo", "»"), Map.entry("deg", "°"), Map.entry("middot", "·"),
            Map.entry("bull", "•"), Map.entry("euro", "€"), Map.entry("pound", "£"),
            Map.entry("copy", "©"), Map.entry("reg", "®"), Map.entry("trade", "™"));

    /** Uma manchete. A imagemUrl fica guardada para um dia mandar foto no Telegram. */
    public record Noticia(
            String tema,
            String titulo,
            String link,
            String fonte,
            ZonedDateTime publicadoEm,
            String resumo,
            String imagemUrl) {
    }

    private record CacheFeed(List<Noticia> itens, Instant quando, Duration validade) {

        boolean fresco(Instant agora) {
            return Duration.between(quando, agora).compareTo(validade) < 0;
        }
    }

    private final HttpClient http;
    private final Map<String, List<String>> feedsPorTema;
    /** Cache por "tema|url", exatamente o par que está no .env. */
    private final Map<String, CacheFeed> cache = new ConcurrentHashMap<>();

    public NoticiasService(HttpClient http, JarvisProperties props) {
        this.http = http;
        this.feedsPorTema = props.feedsPorTema();
    }

    /** Temas configurados, na ordem em que aparecem no .env. */
    public Set<String> temas() {
        return feedsPorTema.keySet();
    }

    /**
     * Manchetes das últimas 24 horas de um tema (ou de todos, com {@link #TODOS}),
     * das mais novas para as mais velhas e sem títulos repetidos.
     */
    public List<Noticia> ultimas(String tema, int limite) {
        List<Noticia> coletadas = new ArrayList<>();
        for (Map.Entry<String, List<String>> entrada : feedsPorTema.entrySet()) {
            if (!TODOS.equals(tema) && !entrada.getKey().equals(tema)) {
                continue;
            }
            for (String url : entrada.getValue()) {
                // Um feed quebrado não pode calar os outros: o erro fica no log e a vida segue.
                coletadas.addAll(doFeed(entrada.getKey(), url));
            }
        }
        return recentes(coletadas, Instant.now(), limite);
    }

    /** Itens de um feed, servidos do cache enquanto ele está fresco (30 min). */
    private List<Noticia> doFeed(String tema, String url) {
        String chave = tema + "|" + url;
        CacheFeed guardado = cache.get(chave);
        if (guardado != null && guardado.fresco(Instant.now())) {
            return guardado.itens();
        }
        try {
            List<Noticia> itens = baixar(tema, url);
            cache.put(chave, new CacheFeed(itens, Instant.now(), VALIDADE_CACHE));
            log.info("Feed {} ({}) atualizado: {} itens", tema, url, itens.size());
            return itens;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            if (guardado != null) {
                // Notícia de meia hora atrás é melhor que radar cego por causa de um 503.
                log.warn("Feed {} ({}) falhou: {}. Usando a última cópia, de {}.",
                        tema, url, motivo(e), guardado.quando());
                cache.put(chave, new CacheFeed(guardado.itens(), Instant.now(), VALIDADE_FALHA));
                return guardado.itens();
            }
            // Guardamos a falha por pouco tempo: sem isso, um feed que pendura somaria o timeout
            // inteiro a cada pergunta no Telegram.
            log.warn("Feed {} ({}) falhou: {}. Seguindo sem ele.", tema, url, motivo(e));
            cache.put(chave, new CacheFeed(List.of(), Instant.now(), VALIDADE_FALHA));
            return List.of();
        }
    }

    /** Algumas exceções de rede vêm sem mensagem; aí o nome da classe é o que informa. */
    private static String motivo(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private List<Noticia> baixar(String tema, String url)
            throws IOException, InterruptedException, SAXException {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(url))
                .timeout(TIMEOUT)
                .header("User-Agent", "Jarvis-Noticias/1.0")
                .GET()
                .build();

        HttpResponse<InputStream> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
        // Alguns servidores (o G1, por exemplo) mandam gzip mesmo sem a gente pedir.
        boolean comprimido = resposta.headers().firstValue("content-encoding")
                .map(v -> v.toLowerCase().contains("gzip"))
                .orElse(false);

        try (InputStream corpo = resposta.body()) {
            if (resposta.statusCode() != 200) {
                throw new IOException("HTTP " + resposta.statusCode());
            }
            try (InputStream dados = comprimido ? new GZIPInputStream(corpo) : corpo) {
                byte[] xml = dados.readNBytes(LIMITE_BYTES);
                return interpretar(ler(new ByteArrayInputStream(xml)), tema);
            }
        }
    }

    /** Lê o XML com o parser endurecido. O InputStream vai direto, para respeitar o encoding declarado. */
    static Document ler(InputStream entrada) throws IOException, SAXException {
        try {
            DocumentBuilderFactory fabrica = DocumentBuilderFactory.newInstance();
            fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            // Sem DOCTYPE não há entidade externa para explorar (XXE): esta é a trava principal.
            fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
            fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            fabrica.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            fabrica.setXIncludeAware(false);
            fabrica.setExpandEntityReferences(false);
            // Sem namespaces: os nomes chegam como "media:content", e comparamos pelo nome local.
            fabrica.setNamespaceAware(false);

            DocumentBuilder construtor = fabrica.newDocumentBuilder();
            // Cinto e suspensório: nada de buscar nada na rede nem escrever no stderr.
            construtor.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            construtor.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(SAXParseException e) {
                }

                @Override
                public void error(SAXParseException e) throws SAXException {
                    throw e;
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXException {
                    throw e;
                }
            });
            return construtor.parse(entrada);
        } catch (javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException("não consegui configurar o parser de XML: " + e.getMessage(), e);
        }
    }

    /** Transforma o documento (RSS 2.0 ou Atom) na nossa lista de notícias. */
    static List<Noticia> interpretar(Document doc, String tema) {
        Element raiz = doc.getDocumentElement();
        String fonte = fonteDoFeed(raiz);

        List<Element> itens = porNomeLocal(raiz, "item");
        boolean atom = itens.isEmpty();
        if (atom) {
            itens = porNomeLocal(raiz, "entry");
        }

        List<Noticia> noticias = new ArrayList<>();
        for (Element item : itens) {
            String titulo = limparHtml(textoDe(item, "title"), Integer.MAX_VALUE);
            if (titulo.isEmpty()) {
                continue;
            }
            // "subtitle" vem primeiro porque o G1 põe o resumo de verdade em <atom:subtitle>, enquanto o
            // <description> dele começa com a <img> e a legenda da foto.
            String bruto = primeiroTextoNaoVazio(item, "subtitle", "description", "content:encoded",
                    "summary", "content");
            noticias.add(new Noticia(
                    tema,
                    titulo,
                    atom ? linkAtom(item) : textoDe(item, "link"),
                    fonte,
                    parseData(primeiroTextoNaoVazio(item, "pubDate", "published", "updated")),
                    limparHtml(bruto, TAMANHO_RESUMO),
                    imagemDe(item, bruto)));
        }
        return noticias;
    }

    /**
     * Filtra as últimas 24 horas, tira títulos repetidos e ordena do mais novo para o mais velho.
     * O "agora" é parâmetro para o teste poder trabalhar com um feed de data fixa.
     */
    static List<Noticia> recentes(List<Noticia> itens, Instant agora, int limite) {
        Instant corte = agora.minus(JANELA);
        Set<String> jaVistos = new LinkedHashSet<>();
        List<Noticia> resultado = new ArrayList<>();

        List<Noticia> ordenadas = new ArrayList<>(itens);
        // Item sem data cai fora: a promessa é "últimas 24 h" e sobre ele não há como afirmar isso.
        ordenadas.removeIf(n -> n.publicadoEm() == null);
        ordenadas.sort(Comparator.comparing((Noticia n) -> n.publicadoEm().toInstant()).reversed());

        for (Noticia n : ordenadas) {
            Instant quando = n.publicadoEm().toInstant();
            if (quando.isBefore(corte) || quando.isAfter(agora.plus(Duration.ofHours(1)))) {
                continue; // velha demais, ou com data no futuro (feed desregulado)
            }
            if (jaVistos.add(chaveDoTitulo(n.titulo())) && resultado.size() < limite) {
                resultado.add(n);
            }
        }
        return resultado;
    }

    private static String chaveDoTitulo(String titulo) {
        return ESPACOS.matcher(titulo.toLowerCase()).replaceAll(" ").trim();
    }

    /** Nome do canal: channel/title no RSS, feed/title no Atom. */
    private static String fonteDoFeed(Element raiz) {
        List<Element> canais = porNomeLocal(raiz, "channel");
        Element dono = canais.isEmpty() ? raiz : canais.get(0);
        for (Element filho : filhosPorNomeLocal(dono, "title")) {
            String titulo = limparHtml(filho.getTextContent(), Integer.MAX_VALUE);
            if (!titulo.isEmpty()) {
                return titulo;
            }
        }
        return "";
    }

    /** No Atom o link é um atributo href, de preferência o rel="alternate". */
    private static String linkAtom(Element item) {
        String reserva = "";
        for (Element link : filhosPorNomeLocal(item, "link")) {
            String href = link.getAttribute("href").trim();
            if (href.isEmpty()) {
                continue;
            }
            String rel = link.getAttribute("rel").trim();
            if (rel.isEmpty() || rel.equals("alternate")) {
                return href;
            }
            if (reserva.isEmpty()) {
                reserva = href;
            }
        }
        return reserva;
    }

    /** Imagem: enclosure, media:content, media:thumbnail e, por último, o primeiro <img> do texto. */
    private static String imagemDe(Element item, String htmlDoResumo) {
        for (String tag : List.of("enclosure", "content", "thumbnail")) {
            for (Element e : porNomeLocal(item, tag)) {
                String url = e.getAttribute("url").trim();
                String tipo = e.getAttribute("type").trim().toLowerCase();
                String meio = e.getAttribute("medium").trim().toLowerCase();
                boolean pareceImagem = tipo.startsWith("image") || meio.equals("image")
                        || (tipo.isEmpty() && meio.isEmpty());
                if (!url.isEmpty() && pareceImagem) {
                    return url;
                }
            }
        }
        Matcher m = TAG_IMG.matcher(htmlDoResumo);
        return m.find() ? m.group(1).trim() : "";
    }

    /** Tira HTML, desfaz as entidades, junta espaços e corta no tamanho pedido. */
    static String limparHtml(String bruto, int tamanhoMaximo) {
        if (bruto == null || bruto.isBlank()) {
            return "";
        }
        String texto = TAGS_HTML.matcher(bruto).replaceAll(" ");
        texto = desfazerEntidades(texto);
        texto = ESPACOS.matcher(texto).replaceAll(" ").trim();
        if (tamanhoMaximo != Integer.MAX_VALUE && texto.length() > tamanhoMaximo) {
            String cortado = texto.substring(0, tamanhoMaximo);
            int ultimoEspaco = cortado.lastIndexOf(' ');
            // Corta na palavra inteira quando dá, para não terminar no meio de uma.
            texto = (ultimoEspaco > tamanhoMaximo - 40 ? cortado.substring(0, ultimoEspaco) : cortado).trim() + "…";
        }
        return texto;
    }

    /**
     * Desfaz as entidades em uma só passada (assim "&amp;eacute;" vira "&eacute;", e não "é").
     * Numéricas resolvem sozinhas; das nomeadas, cobrimos as que de fato aparecem em feeds
     * brasileiros e de língua inglesa — o que não estiver na lista fica como está, sem estragar o texto.
     */
    static String desfazerEntidades(String texto) {
        Matcher m = ENTIDADE.matcher(texto);
        StringBuilder saida = new StringBuilder();
        while (m.find()) {
            String corpo = m.group(1);
            String substituto;
            if (corpo.startsWith("#")) {
                substituto = porCodigo(corpo);
            } else {
                substituto = NOMEADAS.get(corpo);
                if (substituto == null && Character.isUpperCase(corpo.charAt(0))) {
                    // "&Eacute;" é a versão maiúscula de "&eacute;".
                    String minusculo = NOMEADAS.get(corpo.toLowerCase());
                    substituto = minusculo == null ? null : minusculo.toUpperCase();
                }
            }
            m.appendReplacement(saida, Matcher.quoteReplacement(substituto == null ? m.group() : substituto));
        }
        return m.appendTail(saida).toString();
    }

    /** "&#233;" ou "&#xE9;" -> "é". */
    private static String porCodigo(String corpo) {
        try {
            boolean hexa = corpo.length() > 1 && (corpo.charAt(1) == 'x' || corpo.charAt(1) == 'X');
            int codigo = Integer.parseInt(corpo.substring(hexa ? 2 : 1), hexa ? 16 : 10);
            return Character.isValidCodePoint(codigo) ? new String(Character.toChars(codigo)) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** RSS usa RFC 1123 ("Mon, 12 Oct 2026 19:53:17 -0000"); Atom usa ISO-8601. */
    static ZonedDateTime parseData(String bruto) {
        if (bruto == null || bruto.isBlank()) {
            return null;
        }
        String texto = bruto.trim();
        for (DateTimeFormatter formato : List.of(DateTimeFormatter.RFC_1123_DATE_TIME,
                DateTimeFormatter.ISO_OFFSET_DATE_TIME)) {
            try {
                return ZonedDateTime.parse(texto, formato);
            } catch (Exception ignorado) {
                // tenta o próximo formato
            }
        }
        log.debug("Data de publicação em formato desconhecido: {}", texto);
        return null;
    }

    private static String primeiroTextoNaoVazio(Element item, String... tags) {
        for (String tag : tags) {
            String texto = textoDe(item, tag);
            if (!texto.isBlank()) {
                return texto;
            }
        }
        return "";
    }

    private static String textoDe(Element item, String tag) {
        List<Element> achados = filhosPorNomeLocal(item, tag);
        return achados.isEmpty() ? "" : achados.get(0).getTextContent().trim();
    }

    /** Filhos diretos com aquele nome, ignorando prefixo de namespace ("media:content" -> "content"). */
    private static List<Element> filhosPorNomeLocal(Element pai, String nome) {
        List<Element> achados = new ArrayList<>();
        NodeList filhos = pai.getChildNodes();
        for (int i = 0; i < filhos.getLength(); i++) {
            Node filho = filhos.item(i);
            if (filho instanceof Element e && combina(e.getNodeName(), nome)) {
                achados.add(e);
            }
        }
        return achados;
    }

    /** Qualquer descendente com aquele nome (media:thumbnail às vezes vem dentro de media:content). */
    private static List<Element> porNomeLocal(Element raiz, String nome) {
        List<Element> achados = new ArrayList<>();
        NodeList todos = raiz.getElementsByTagName("*");
        for (int i = 0; i < todos.getLength(); i++) {
            Node no = todos.item(i);
            if (no instanceof Element e && combina(e.getNodeName(), nome)) {
                achados.add(e);
            }
        }
        return achados;
    }

    private static boolean combina(String nomeDoNo, String procurado) {
        String local = nomeDoNo.contains(":") ? nomeDoNo.substring(nomeDoNo.indexOf(':') + 1) : nomeDoNo;
        String alvo = procurado.contains(":") ? procurado.substring(procurado.indexOf(':') + 1) : procurado;
        return local.equalsIgnoreCase(alvo);
    }
}
