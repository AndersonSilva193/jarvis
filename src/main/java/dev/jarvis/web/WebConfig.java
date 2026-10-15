package dev.jarvis.web;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import dev.jarvis.config.JarvisProperties;

/**
 * CORS liberado só para as origens de HUD_ORIGEM. Lista vazia = nenhuma origem de navegador,
 * que é o certo quando a HUD fala com a API pelo nginx, no mesmo domínio.
 *
 * O CORS é aplicado por um filtro, e não pelo addCorsMappings do WebMvc, porque o TokenHudFiltro
 * responde 401 antes do DispatcherServlet: se o CORS vivesse só lá dentro, a resposta de token
 * inválido chegaria ao navegador sem Access-Control-Allow-Origin e a HUD veria um erro de rede
 * opaco, sem conseguir diferenciar "token errado" de "API fora do ar". Rodando antes do filtro de
 * token, todo 401 sai com os cabeçalhos certos.
 */
@Configuration
public class WebConfig {

    private static final Logger log = LoggerFactory.getLogger(WebConfig.class);

    private final List<String> origens;

    public WebConfig(JarvisProperties props) {
        this.origens = props.origensDaHud();
    }

    @Bean
    public FilterRegistrationBean<CorsFilter> filtroCors() {
        FilterRegistrationBean<CorsFilter> registro =
                new FilterRegistrationBean<>(new CorsFilter(fonteDeCors()));
        registro.addUrlPatterns("/api/*");
        // Antes do TokenHudFiltro, que é @Component sem @Order (ou seja, último da fila).
        registro.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registro;
    }

    private CorsConfigurationSource fonteDeCors() {
        UrlBasedCorsConfigurationSource fonte = new UrlBasedCorsConfigurationSource();
        // O agente do PC não é navegador: /api/pc não leva cabeçalho de CORS nenhum. Config vazia
        // = nenhuma origem liberada. Vem ANTES da liberação de propósito: a fonte devolve o
        // primeiro padrão que casa, e sem isto o /api/** daria a uma página na origem da HUD o
        // direito de ler respostas de /api/pc. Inofensivo sem o PC_TOKEN, mas é defesa em
        // profundidade de duas linhas.
        fonte.registerCorsConfiguration("/api/pc/**", new CorsConfiguration());
        if (origens.isEmpty()) {
            log.warn("HUD_ORIGEM não configurado: nenhuma origem de navegador poderá chamar a API "
                    + "(chamadas pelo nginx, no mesmo domínio, continuam funcionando).");
            return fonte;
        }
        log.info("CORS liberado para: {}", origens);
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(origens);
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setMaxAge(3600L);
        fonte.registerCorsConfiguration("/api/**", config);
        return fonte;
    }
}
