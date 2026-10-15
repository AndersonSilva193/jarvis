package dev.jarvis.tela;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;

/**
 * Busca uma página da web para a HUD mostrar: a prévia (os metadados Open Graph) e se ela aceita
 * ser exibida dentro de outra página.
 *
 * <p>Toda a proteção contra SSRF está no {@link GuardaSsrf}, e é obrigatória aqui: é a VPS que faz
 * a requisição, para um endereço que o modelo ditou. Por isso o cliente HTTP deste serviço é o
 * {@code httpSemRedirecionar} — cada salto de {@code Location:} é revalidado antes de ser seguido.
 *
 * <p>Nada de credencial sai daqui: sem cookie, sem Authorization. E do corpo só aproveitamos
 * quatro metadados; o HTML cru nunca volta ao modelo nem à tela.
 */
@Service
public class PaginaService {

    private static final Logger log = LoggerFactory.getLogger(PaginaService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    /** Teto do corpo: página de terceiro, e a VPS tem memória limitada. */
    private static final int LIMITE_BYTES = 1024 * 1024;
    /** Depois do </head> não há mais metadado que interesse. */
    private static final int LIMITE_CABECA = 64 * 1024;
    private static final int MAX_DESCRICAO = 300;

    private static final Pattern FIM_DA_CABECA = Pattern.compile("</head\\s*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern META = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATRIBUTO =
            Pattern.compile("(property|name|content)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\">]+))",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TITULO =
            Pattern.compile("<title[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FRAME_ANCESTORS =
            Pattern.compile("frame-ancestors([^;]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHARSET =
            Pattern.compile("charset\\s*=\\s*\"?([A-Za-z0-9_:.+-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAGS = Pattern.compile("<[^>]*>");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    /**
     * A prévia de uma página.
     *
     * @param incorporavel se ela aceita ser exibida dentro de outra. Falso quando manda
     *                     X-Frame-Options ou frame-ancestors que nos barram — aí a HUD mostra só a
     *                     prévia com o link, em vez de um quadro vazio.
     */
    public record Previa(String url, String titulo, String descricao, String imagem,
            String site, boolean incorporavel) {
    }

    private final HttpClient http;
    private final List<String> origensDaHud;

    public PaginaService(@Qualifier("httpSemRedirecionar") HttpClient http, JarvisProperties props) {
        this.http = http;
        this.origensDaHud = props.origensDaHud();
    }

    /**
     * Baixa a página e monta a prévia, seguindo no máximo {@value GuardaSsrf#MAX_REDIRECIONAMENTOS}
     * redirecionamentos e revalidando cada destino.
     *
     * @throws GuardaSsrf.DestinoRecusadoException quando o endereço não é provadamente público
     */
    public Previa baixar(String url) throws IOException, InterruptedException {
        URI destino = GuardaSsrf.exigirPublico(GuardaSsrf.validarForma(url));

        for (int salto = 0; salto <= GuardaSsrf.MAX_REDIRECIONAMENTOS; salto++) {
            HttpRequest requisicao = HttpRequest.newBuilder(destino)
                    .timeout(TIMEOUT)
                    .header("User-Agent", "Jarvis-Pagina/1.0")
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "pt-BR,pt;q=0.9")
                    .GET()
                    .build();

            HttpResponse<InputStream> resposta =
                    http.send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
            int status = resposta.statusCode();

            if (status >= 300 && status < 400) {
                try (InputStream ignorado = resposta.body()) {
                    // Nada a ler: o que interessa é o Location.
                }
                if (salto == GuardaSsrf.MAX_REDIRECIONAMENTOS) {
                    throw new GuardaSsrf.DestinoRecusadoException("essa página redireciona demais");
                }
                destino = GuardaSsrf.proximoSalto(destino,
                        resposta.headers().firstValue("location").orElse(null));
                continue;
            }

            try (InputStream corpo = resposta.body()) {
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
                boolean comprimido = resposta.headers().firstValue("content-encoding")
                        .map(v -> v.toLowerCase(Locale.ROOT).contains("gzip"))
                        .orElse(false);
                try (InputStream dados = comprimido ? new GZIPInputStream(corpo) : corpo) {
                    byte[] bytes = dados.readNBytes(LIMITE_BYTES);
                    String html = new String(bytes, charsetDe(
                            resposta.headers().firstValue("content-type").orElse("")));
                    boolean incorporavel = incorporavel(
                            resposta.headers().firstValue("x-frame-options").orElse(""),
                            resposta.headers().firstValue("content-security-policy").orElse(""),
                            origensDaHud);
                    return interpretar(html, destino.toString(), incorporavel);
                }
            }
        }
        throw new GuardaSsrf.DestinoRecusadoException("essa página redireciona demais");
    }

    /** Os metadados Open Graph da cabeça do documento, com o <title> como reserva. */
    static Previa interpretar(String html, String urlFinal, boolean incorporavel) {
        String cabeca = cabecaDe(html);
        String titulo = meta(cabeca, "og:title");
        if (titulo.isEmpty()) {
            Matcher m = TITULO.matcher(cabeca);
            titulo = m.find() ? limpar(m.group(1), 200) : "";
        }
        return new Previa(
                urlFinal,
                titulo.isEmpty() ? urlFinal : titulo,
                limpar(meta(cabeca, "og:description"), MAX_DESCRICAO),
                meta(cabeca, "og:image"),
                meta(cabeca, "og:site_name"),
                incorporavel);
    }

    /**
     * O conteúdo de um {@code <meta>} por propriedade.
     *
     * Lê os atributos um a um em vez de casar a tag inteira de uma vez, porque {@code property} e
     * {@code content} aparecem em qualquer ordem, com aspas simples, duplas ou nenhuma.
     */
    static String meta(String cabeca, String propriedade) {
        Matcher tags = META.matcher(cabeca);
        while (tags.find()) {
            String tag = tags.group();
            String chave = "";
            String conteudo = "";
            Matcher atributos = ATRIBUTO.matcher(tag);
            while (atributos.find()) {
                String nome = atributos.group(1).toLowerCase(Locale.ROOT);
                String valor = primeiroNaoNulo(atributos.group(3), atributos.group(4), atributos.group(5));
                if (nome.equals("content")) {
                    conteudo = valor;
                } else if (chave.isEmpty()) {
                    // Alguns sites usam name="og:title" em vez de property=; aceitamos os dois.
                    chave = valor;
                }
            }
            if (propriedade.equalsIgnoreCase(chave.trim()) && !conteudo.isBlank()) {
                return limpar(conteudo, 2048);
            }
        }
        return "";
    }

    /**
     * Se a página aceita ser exibida dentro da HUD.
     *
     * Conservador por natureza: na dúvida sobre uma lista de origens, só liberamos quando ela casa
     * com alguma origem configurada em HUD_ORIGEM. Marcar incorporável uma página que o navegador
     * vai recusar renderiza um quadro em branco, o que é pior do que mostrar só a prévia.
     */
    static boolean incorporavel(String xFrameOptions, String csp, List<String> origensDaHud) {
        String opcoes = xFrameOptions == null ? "" : xFrameOptions.trim().toLowerCase(Locale.ROOT);
        if (opcoes.contains("deny") || opcoes.contains("sameorigin") || opcoes.startsWith("allow-from")) {
            return false;
        }
        Matcher m = FRAME_ANCESTORS.matcher(csp == null ? "" : csp);
        if (!m.find()) {
            return true;
        }
        String permitidos = m.group(1).trim().toLowerCase(Locale.ROOT);
        if (permitidos.isEmpty() || permitidos.contains("'none'")) {
            return false;
        }
        if (permitidos.contains("*") && !permitidos.contains("*.")) {
            return true;
        }
        for (String origem : origensDaHud) {
            String host = hostDe(origem);
            if (!host.isEmpty() && permitidos.contains(host)) {
                return true;
            }
        }
        return false;
    }

    private static String hostDe(String origem) {
        try {
            String host = URI.create(origem.trim()).getHost();
            return host == null ? "" : host.toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String cabecaDe(String html) {
        String inicio = html.length() > LIMITE_CABECA ? html.substring(0, LIMITE_CABECA) : html;
        Matcher fim = FIM_DA_CABECA.matcher(inicio);
        return fim.find() ? inicio.substring(0, fim.start()) : inicio;
    }

    /** O charset declarado no content-type, com UTF-8 de reserva. */
    private static Charset charsetDe(String contentType) {
        Matcher m = CHARSET.matcher(contentType == null ? "" : contentType);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1).trim());
            } catch (RuntimeException e) {
                log.debug("Charset desconhecido em '{}'; usando UTF-8", contentType);
            }
        }
        return StandardCharsets.UTF_8;
    }

    /** Tira tag, junta espaço e corta no tamanho: isto vai para a tela, não é para parsear HTML. */
    private static String limpar(String bruto, int max) {
        if (bruto == null || bruto.isBlank()) {
            return "";
        }
        String texto = ESPACOS.matcher(TAGS.matcher(bruto).replaceAll(" ")).replaceAll(" ").trim();
        texto = texto.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
        return texto.length() <= max ? texto : texto.substring(0, max).trim() + "…";
    }

    private static String primeiroNaoNulo(String... valores) {
        for (String valor : valores) {
            if (valor != null) {
                return valor;
            }
        }
        return "";
    }
}
