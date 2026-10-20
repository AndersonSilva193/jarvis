package dev.jarvis.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Todas as configurações do bot, lidas do application.yml (que por sua vez lê variáveis de ambiente / .env).
 */
@ConfigurationProperties(prefix = "jarvis")
public record JarvisProperties(
        String nomeUsuario,
        String fusoHorario,
        /* Formato: "nome=https://url,outro=https://url2" */
        String sites,
        Telegram telegram,
        Anthropic anthropic,
        Clima clima,
        Noticias noticias,
        Hud hud,
        Personalidade personalidade,
        Voz voz,
        BuscaWeb buscaWeb,
        Youtube youtube,
        Pc pc,
        ComputerUse computerUse,
        Google google,
        Memoria memoria) {

    private static final Logger log = LoggerFactory.getLogger(JarvisProperties.class);

    @org.springframework.boot.context.properties.bind.ConstructorBinding
    public JarvisProperties { }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, null, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            Google google) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, google, null);
    }

    public JarvisProperties(String nomeUsuario, String fusoHorario, String sites, Telegram telegram,
            Anthropic anthropic, Clima clima, Noticias noticias, Hud hud,
            Personalidade personalidade, Voz voz, BuscaWeb buscaWeb, Youtube youtube, Pc pc,
            Google google, Memoria memoria) {
        this(nomeUsuario, fusoHorario, sites, telegram, anthropic, clima, noticias, hud,
                personalidade, voz, buscaWeb, youtube, pc, null, google, memoria);
    }

    /** Computer use fica desligado ate ser configurado e homologado no Windows. */
    public record ComputerUse(boolean ativo, String provedor, String modeloAnthropic,
            String modeloOpenai, String openaiApiKey, int maxCiclos, int maxAcoes,
            int duracaoSegundos) { }

    public record Telegram(String token, long usuarioPermitido) {
    }

    /**
     * Acesso à Messages API.
     *
     * @param cacheMinimoTokens menor prefixo que o modelo aceita cachear. Abaixo disso a API ignora o
     *                          cache_control em silêncio, então nem mandamos o marcador. Muda por
     *                          modelo: 512 no Haiku 5.5 e no Sonnet 5.5, 4096 no Haiku 4.5.
     */
    public record Anthropic(String apiKey, String modelo, int maxTokens, int cacheMinimoTokens) {
    }

    /**
     * Busca na web pela ferramenta server-side da Messages API: a definição vai no array de
     * ferramentas e quem executa é o servidor da Anthropic, não o Jarvis.
     *
     * @param tipo    versão do tipo da ferramenta. "web_search_20250305" é a básica e roda em
     *                qualquer modelo atual; as versões de 2026 fazem filtragem dinâmica, que roda a
     *                busca dentro do code execution e exige modelo com programmatic tool calling.
     *                Confira pelo Models API (capabilities.server_tools.web_search) antes de trocar.
     * @param maxUsos teto de buscas por pedido. Cada busca é cobrada à parte dos tokens.
     */
    public record BuscaWeb(boolean ativa, String tipo, int maxUsos) {
    }

    /** Cidade do clima e suas coordenadas (a Open-Meteo trabalha com latitude/longitude). */
    public record Clima(String cidade, double latitude, double longitude) {
    }

    /** Feeds de notícias no formato "tema|url,tema|url". */
    public record Noticias(String feeds) {
    }

    /** API da HUD: token exigido nos endpoints e origens liberadas no CORS. */
    public record Hud(String token, String origens) {
    }

    /**
     * Voz da HUD.
     *
     * @param provedor   "elevenlabs" ou "navegador". Em "navegador" nada é enviado à ElevenLabs.
     * @param reservaPct fatia da cota que o Jarvis se recusa a gastar, em por cento do limite.
     */
    public record Voz(String provedor, String apiKey, String vozId, String modelo, int reservaPct) {
    }

    /** Arquivo markdown com a personalidade do Jarvis, lido em tempo de execução (sem rebuild). */
    public record Personalidade(String arquivo) {
    }

    /**
     * Chave da YouTube Data API v3, usada pela ferramenta que toca vídeo e música na HUD.
     *
     * Cada {@code search.list} custa 100 das 10.000 unidades diárias de cota — daí o cache de
     * 24 horas por consulta no {@code YoutubeService}. Sem chave, a ferramenta diz isso ao modelo.
     */
    public record Youtube(String apiKey) {
    }

    /**
     * Agente que roda no PC Windows e busca comandos por long polling.
     *
     * O token é separado do {@code HUD_TOKEN} de propósito: quem tem a HUD não manda na máquina, e
     * um token vazado numa tela aberta não vira desligamento de PC. A lista de programas liberados
     * <b>não</b> mora aqui — ela é do {@code config.properties} do agente, que a reporta no
     * cabeçalho {@code X-Pc-Apps} de cada consulta. Assim o enum de {@code abrir_app} tem uma
     * única fonte de verdade, no PC.
     */
    public record Pc(String token, String operacoesPasta, int edicaoMaxBytes,
            int edicaoMaxOperacoes, int edicaoMaxBytesResultado) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Pc { }
        public Pc(String token, String operacoesPasta) {
            this(token, operacoesPasta, 1024 * 1024, 10, 1024 * 1024);
        }
        public Pc(String token) { this(token, "/data/pc/operacoes.json"); }
    }

    /** Credenciais OAuth da integração opcional com uma conta Google. */
    public record Google(String clientId, String clientSecret, String redirectUri,
            String chaveCofre, String pastaCredenciais) { }

    /** Volumes separados para registros e marcadores permanentes de exclusão. */
    public record Memoria(String pasta, String pastaExclusoes) { }

    /** Se a busca no YouTube está disponível. Sem chave, a ferramenta avisa em vez de falhar. */
    public boolean youtubeConfigurado() {
        return youtube != null && youtube.apiKey() != null && !youtube.apiKey().isBlank();
    }

    /** Token do agente do PC. Vazio = /api/pc recusa tudo com 401 (em dúvida, fecha). */
    public String tokenDoPc() {
        return pc == null || pc.token() == null ? "" : pc.token();
    }

    /** Se o controle do PC está configurado. Sem token, a rota do agente recusa tudo. */
    public boolean pcConfigurado() {
        return !tokenDoPc().isBlank();
    }

    public ZoneId zona() {
        return ZoneId.of(fusoHorario == null || fusoHorario.isBlank() ? "America/Sao_Paulo" : fusoHorario);
    }

    /**
     * Se a busca na web está ligada. Null-safe de propósito: quem monta as properties à mão (testes,
     * chamadas internas) deixa o bloco de fora e a busca simplesmente fica desligada.
     */
    public boolean buscaWebAtiva() {
        return buscaWeb != null && buscaWeb.ativa();
    }

    /** Caminho do arquivo de personalidade. Dentro do container é o volume montado em /app/config. */
    public Path arquivoDaPersonalidade() {
        String caminho = personalidade == null ? null : personalidade.arquivo();
        return Path.of(caminho == null || caminho.isBlank() ? "/app/config/personalidade.md" : caminho);
    }

    /** Origens liberadas no CORS da HUD. Lista vazia significa nenhuma origem de navegador. */
    public List<String> origensDaHud() {
        String config = hud == null ? null : hud.origens();
        if (config == null || config.isBlank()) {
            return List.of();
        }
        return Arrays.stream(config.split(","))
                .map(String::trim)
                .filter(origem -> !origem.isBlank())
                .toList();
    }

    /**
     * Verdadeiro quando a ElevenLabs está escolhida E tem chave e voz configuradas. Faltando
     * qualquer um dos três, a HUD fala pela voz do navegador — melhor do que descobrir isso
     * no meio de um briefing mudo.
     */
    public boolean vozRemotaConfigurada() {
        return voz != null
                && "elevenlabs".equalsIgnoreCase(voz.provedor())
                && voz.apiKey() != null && !voz.apiKey().isBlank()
                && voz.vozId() != null && !voz.vozId().isBlank();
    }

    /** Modelo de voz, com o Flash v2.5 de padrão: fala português e custa metade por caractere. */
    public String modeloDeVoz() {
        String modelo = voz == null ? null : voz.modelo();
        return modelo == null || modelo.isBlank() ? "eleven_flash_v2_5" : modelo;
    }

    /** Fatia intocável da cota. Limitada a 0-100 para um valor errado no .env não travar a voz. */
    public int reservaDeVozPct() {
        return Math.clamp(voz == null ? 5 : voz.reservaPct(), 0, 100);
    }

    /**
     * Converte a string de feeds em um mapa tema -> lista de URLs, mantendo a ordem.
     * O mesmo tema pode aparecer várias vezes ("tecnologia|url1,tecnologia|url2"): os feeds se somam.
     */
    public Map<String, List<String>> feedsPorTema() {
        Map<String, List<String>> mapa = new LinkedHashMap<>();
        String config = noticias == null ? null : noticias.feeds();
        if (config == null || config.isBlank()) {
            return mapa;
        }
        for (String par : config.split(",")) {
            String[] partes = par.trim().split("\\|", 2);
            if (partes.length == 2 && !partes[0].isBlank() && !partes[1].isBlank()) {
                mapa.computeIfAbsent(partes[0].trim(), tema -> new ArrayList<>()).add(partes[1].trim());
            }
        }
        return mapa;
    }

    /**
     * Converte a string de sites monitorados em um mapa nome -> URL, mantendo a ordem.
     *
     * Nome repetido mantém a <b>primeira</b> ocorrência e avisa em WARN: antes a segunda
     * sobrescrevia a primeira em silêncio, e um site simplesmente deixava de ser checado.
     * Entrada mal formada (sem {@code =}, nome vazio, URL que não seja http/https) também
     * é ignorada com WARN, em vez de virar uma requisição que falha em todo briefing.
     */
    public Map<String, String> sitesMonitorados() {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (sites == null || sites.isBlank()) {
            return mapa;
        }
        for (String par : sites.split(",")) {
            String entrada = par.trim();
            if (entrada.isEmpty()) {
                continue;
            }
            String[] partes = entrada.split("=", 2);
            if (partes.length < 2) {
                log.warn("SITES_MONITORADOS: entrada sem '=' ignorada: {}", semQuery(entrada));
                continue;
            }
            String nome = partes[0].trim();
            String url = partes[1].trim();
            if (nome.isBlank()) {
                log.warn("SITES_MONITORADOS: entrada sem nome ignorada (url {})", semQuery(url));
                continue;
            }
            if (!urlDeSiteValida(url)) {
                log.warn("SITES_MONITORADOS: url invalida no site '{}', entrada ignorada: {}",
                        nome, semQuery(url));
                continue;
            }
            if (mapa.containsKey(nome)) {
                log.warn("SITES_MONITORADOS: nome '{}' repetido; mantida a primeira url e ignorada "
                        + "a seguinte: {}", nome, semQuery(url));
                continue;
            }
            mapa.put(nome, url);
        }
        return mapa;
    }

    /** Só http e https, com host: o resto nunca daria uma checagem útil. */
    private static boolean urlDeSiteValida(String url) {
        if (url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            String esquema = uri.getScheme();
            return uri.getHost() != null && !uri.getHost().isBlank()
                    && ("http".equalsIgnoreCase(esquema) || "https".equalsIgnoreCase(esquema));
        } catch (IllegalArgumentException naoEhUri) {
            return false;
        }
    }

    /**
     * Corta a query string antes de a URL entrar no log: ela é o lugar onde token e chave
     * costumam viajar, e aviso de configuração não é motivo para escrever segredo em disco.
     */
    static String semQuery(String url) {
        int interrogacao = url.indexOf('?');
        return interrogacao < 0 ? url : url.substring(0, interrogacao) + "?...";
    }
}
