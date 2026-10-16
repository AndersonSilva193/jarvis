package dev.jarvis.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
 * Exige o token da HUD em tudo sob /api, menos em /api/saude.
 *
 * O normal é vir em "Authorization: Bearer <token>". GET de eventos, anexos e áudio de voz
 * aceita também "?token=": nem o EventSource nem o elemento <audio> sabem mandar header nenhum.
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
        String uri = requisicao.getRequestURI();
        // Fora de /api não há o que proteger aqui. E /api/pc é do agente do PC: quem exige o
        // PC_TOKEN lá é o TokenPcFiltro, com o mesmo predicado, para os dois conjuntos serem
        // disjuntos por construção (ver o javadoc de TokenPcFiltro.rotaDoPc).
        return !uri.startsWith("/api") || TokenPcFiltro.rotaDoPc(uri);
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
                || ("/api/google/oauth/callback".equals(requisicao.getRequestURI())
                    && HttpMethod.GET.matches(requisicao.getMethod()))
                || HttpMethod.OPTIONS.matches(requisicao.getMethod());
    }

    private static String tokenDaRequisicao(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO)) {
            return cabecalho.substring(PREFIXO.length()).trim();
        }
        // Exceção restrita aos GETs de SSE, anexos e áudio de voz previamente registrados.
        String rota = requisicao.getRequestURI();
        if (HttpMethod.GET.matches(requisicao.getMethod()) &&
                (ROTA_EVENTOS.equals(rota) || "/api/eventos".equals(rota)
                || rota.matches("/api/anexos/[a-fA-F0-9-]{36}")
                || rota.matches("/api/voz/audio/[a-f0-9]{64}"))) {
            return requisicao.getParameter("token");
        }
        return null;
    }

    /**
     * Compara em tempo constante. A conta mora no {@link ComparadorDeToken}, que é o único lugar
     * do projeto com criptografia — o filtro do PC e o código de confirmação usam o mesmo.
     */
    static boolean tokenValido(String fornecido, String esperado) {
        return ComparadorDeToken.igual(fornecido, esperado);
    }
}
