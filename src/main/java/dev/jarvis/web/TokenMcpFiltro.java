package dev.jarvis.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.ComparadorDeToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exige o MCP_TOKEN em tudo sob /api/mcp — e nada mais.
 *
 * <p>Terceiro domínio de confiança, separado pelo mesmo motivo do {@link TokenPcFiltro}: quem chama
 * aqui são os agentes do escritório (Hermes Agent, no PC), que leem a internet. Um token vazado lá
 * não pode abrir a HUD nem o controle do PC, e o da HUD não abre isto. Sem {@code ?token=}: o
 * cliente MCP sempre manda cabeçalho, e token em query string vai parar no log do nginx.
 *
 * <p>Os três filtros são disjuntos pelos predicados {@link TokenPcFiltro#rotaDoPc(String)} e
 * {@link #rotaDoMcp(String)}, que o {@link TokenHudFiltro} também consulta. É essa a invariante.
 */
@Component
public class TokenMcpFiltro extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenMcpFiltro.class);
    private static final String PREFIXO = "Bearer ";
    static final String ROTA_MCP = "/api/mcp";

    private final String tokenEsperado;

    public TokenMcpFiltro(JarvisProperties props) {
        this.tokenEsperado = props.tokenDoMcp();
        if (tokenEsperado.isBlank()) {
            log.warn("MCP_TOKEN não configurado: /api/mcp vai recusar tudo com 401 e o escritório "
                    + "não fala com o Jarvis. Gere um token (openssl rand -hex 32) e coloque no .env.");
        }
    }

    /** Casa {@code /api/mcp} e {@code /api/mcp/...}, mas não {@code /api/mcpalgo}. */
    static boolean rotaDoMcp(String uri) {
        return ROTA_MCP.equals(uri) || uri.startsWith(ROTA_MCP + "/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !rotaDoMcp(requisicao.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        if (ComparadorDeToken.igual(tokenDoCabecalho(requisicao), tokenEsperado)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }
        log.warn("Chamada MCP recusada em {} {} (token ausente ou inválido)",
                requisicao.getMethod(), requisicao.getRequestURI());
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // A mesma frase dos outros dois filtros: nada de oráculo sobre qual segredo foi tentado.
        resposta.getWriter().write("{\"erro\":\"token ausente ou invalido\"}");
    }

    private static String tokenDoCabecalho(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO)) {
            return cabecalho.substring(PREFIXO.length()).trim();
        }
        return null;
    }
}
