package dev.jarvis.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import dev.jarvis.config.JarvisProperties;

/**
 * CORS liberado só para as origens de HUD_ORIGEM. Lista vazia = nenhuma origem de navegador,
 * que é o certo quando a HUD fala com a API pelo nginx, no mesmo domínio.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final List<String> origens;

    public WebConfig(JarvisProperties props) {
        this.origens = props.origensDaHud();
    }

    @Override
    public void addCorsMappings(CorsRegistry registro) {
        if (origens.isEmpty()) {
            log.warn("HUD_ORIGEM não configurado: nenhuma origem de navegador poderá chamar a API "
                    + "(chamadas pelo nginx, no mesmo domínio, continuam funcionando).");
            return;
        }
        log.info("CORS liberado para: {}", origens);
        registro.addMapping("/api/**")
                .allowedOrigins(origens.toArray(String[]::new))
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .maxAge(3600);
    }
}
