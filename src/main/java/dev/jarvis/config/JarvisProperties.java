package dev.jarvis.config;

import java.time.ZoneId;
import java.util.LinkedHashMap;
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
        Anthropic anthropic) {

    public record Telegram(String token, long usuarioPermitido) {
    }

    public record Anthropic(String apiKey, String modelo, int maxTokens) {
    }

    public ZoneId zona() {
        return ZoneId.of(fusoHorario == null || fusoHorario.isBlank() ? "America/Sao_Paulo" : fusoHorario);
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
