package dev.jarvis.config;

import java.time.ZoneId;
import java.util.ArrayList;
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
        Noticias noticias) {

    public record Telegram(String token, long usuarioPermitido) {
    }

    public record Anthropic(String apiKey, String modelo, int maxTokens) {
    }

    /** Cidade do clima e suas coordenadas (a Open-Meteo trabalha com latitude/longitude). */
    public record Clima(String cidade, double latitude, double longitude) {
    }

    /** Feeds de notícias no formato "tema|url,tema|url". */
    public record Noticias(String feeds) {
    }

    public ZoneId zona() {
        return ZoneId.of(fusoHorario == null || fusoHorario.isBlank() ? "America/Sao_Paulo" : fusoHorario);
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
