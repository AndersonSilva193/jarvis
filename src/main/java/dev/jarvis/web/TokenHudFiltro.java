package dev.jarvis.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import dev.jarvis.config.JarvisProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Exige o token da HUD em tudo sob /api, menos em /api/saude.
 *
 * O normal é vir em "Authorization: Bearer <token>". GET de eventos e anexos aceita
 * também "?token="; o EventSource do navegador não sabe mandar header nenhum.
 */
@Component
public class TokenHudFiltro extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenHudFiltro.class);
    private static final String PREFIXO = "Bearer ";
    private static final String ROTA_SAUDE = "/api/saude";
    private static final String ROTA_EVENTOS = "/api/briefing/eventos";

    private final String tokenEsperado;

    public TokenHudFiltro(JarvisProperties props) {
        this.tokenEsperado = props.hud() == null ? "" : props.hud().token();
        if (tokenEsperado == null || tokenEsperado.isBlank()) {
            log.warn("HUD_TOKEN não configurado: a API vai recusar todas as chamadas com 401. "
                    + "Gere um token (openssl rand -hex 32) e coloque no .env para usar a HUD.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        // Fora de /api não há o que proteger aqui.
        return !requisicao.getRequestURI().startsWith("/api");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        if (liberado(requisicao)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }
        if (tokenValido(tokenDaRequisicao(requisicao), tokenEsperado)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }
        log.warn("Chamada recusada em {} {} (token ausente ou inválido)",
                requisicao.getMethod(), requisicao.getRequestURI());
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resposta.getWriter().write("{\"erro\":\"token ausente ou invalido\"}");
    }

    /**
     * Saúde é público, e o preflight do CORS passa direto: o navegador manda OPTIONS sem o
     * header Authorization, então barrar aqui quebraria o CORS antes da requisição real.
     */
    private static boolean liberado(HttpServletRequest requisicao) {
        return ROTA_SAUDE.equals(requisicao.getRequestURI())
                || HttpMethod.OPTIONS.matches(requisicao.getMethod());
    }

    private static String tokenDaRequisicao(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO)) {
            return cabecalho.substring(PREFIXO.length()).trim();
        }
        // Exceção restrita aos GETs de SSE e anexos previamente registrados.
        String rota = requisicao.getRequestURI();
        if (HttpMethod.GET.matches(requisicao.getMethod()) &&
                (ROTA_EVENTOS.equals(rota) || "/api/eventos".equals(rota)
                || rota.matches("/api/anexos/[a-fA-F0-9-]{36}"))) {
            return requisicao.getParameter("token");
        }
        return null;
    }

    /**
     * Compara em tempo constante. Usamos o SHA-256 dos dois valores antes do MessageDigest.isEqual
     * porque ele só é constante para arrays do mesmo tamanho — com o hash, o tamanho do token
     * nunca influencia o tempo da comparação.
     */
    static boolean tokenValido(String fornecido, String esperado) {
        if (fornecido == null || fornecido.isBlank() || esperado == null || esperado.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(sha256(fornecido), sha256(esperado));
    }

    private static byte[] sha256(String valor) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 deveria existir em qualquer JVM", e);
        }
    }
}
