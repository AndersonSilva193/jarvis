package dev.jarvis.config;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Personalidade personalidade) {

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

    /** Cidade do clima e suas coordenadas (a Open-Meteo trabalha com latitude/longitude). */
    public record Clima(String cidade, double latitude, double longitude) {
    }

    /** Feeds de notícias no formato "tema|url,tema|url". */
    public record Noticias(String feeds) {
    }

    /** API da HUD: token exigido nos endpoints e origens liberadas no CORS. */
    public record Hud(String token, String origens) {
    }

    /** Arquivo markdown com a personalidade do Jarvis, lido em tempo de execução (sem rebuild). */
    public record Personalidade(String arquivo) {
    }

    public ZoneId zona() {
        return ZoneId.of(fusoHorario == null || fusoHorario.isBlank() ? "America/Sao_Paulo" : fusoHorario);
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

    /** Converte a string de sites monitorados em um mapa nome -> URL, mantendo a ordem. */
    public Map<String, String> sitesMonitorados() {
        Map<String, String> mapa = new LinkedHashMap<>();
        if (sites == null || sites.isBlank()) {
            return mapa;
        }
        for (String par : sites.split(",")) {
            String[] partes = par.trim().split("=", 2);
            if (partes.length == 2 && !partes[0].isBlank() && !partes[1].isBlank()) {
                mapa.put(partes[0].trim(), partes[1].trim());
            }
        }
        return mapa;
    }
}
