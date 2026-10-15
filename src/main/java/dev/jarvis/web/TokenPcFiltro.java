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
 * Exige o PC_TOKEN em tudo sob /api/pc — e nada mais.
 *
 * É um filtro separado do {@link TokenHudFiltro} de propósito. Aquele concentra três políticas
 * acopladas (qual token vale, o que é liberado, e a lista estreita de GETs que aceitam
 * {@code ?token=}); ensiná-lo a escolher entre dois segredos poria um {@code if} por domínio de
 * confiança em cada uma, e um {@code ||} errado funde HUD e PC. Em especial, o {@code ?token=} que
 * existe para o {@code EventSource} e o {@code <audio>} <b>não pode valer aqui</b>: o PC_TOKEN iria
 * para a query string e dali para o log do nginx, para o histórico do shell e para o referer.
 * Separando os filtros, esse acidente é impossível por construção — este simplesmente não tem o
 * código do {@code ?token=}.
 *
 * <p>A ordem entre os dois filtros é irrelevante, porque os {@code shouldNotFilter} são disjuntos
 * pelo mesmo predicado {@link #rotaDoPc(String)}. Essa é a invariante a preservar.
 */
@Component
public class TokenPcFiltro extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TokenPcFiltro.class);
    private static final String PREFIXO = "Bearer ";
    static final String ROTA_PC = "/api/pc";

    private final String tokenEsperado;

    public TokenPcFiltro(JarvisProperties props) {
        this.tokenEsperado = props.tokenDoPc();
        if (tokenEsperado.isBlank()) {
            log.warn("PC_TOKEN não configurado: /api/pc vai recusar tudo com 401 e o controle do "
                    + "PC fica desligado. Gere um token (openssl rand -hex 32) e coloque no .env.");
        }
    }

    /**
     * Casa {@code /api/pc} e {@code /api/pc/...}, mas não {@code /api/pcalgo}.
     *
     * <p>Um {@code startsWith("/api/pc")} solto faria o {@link TokenHudFiltro} desistir de uma
     * rota que este filtro também não pegaria — ou seja, rota aberta sem token nenhum. É o bug
     * clássico dessa troca, e manter o predicado num único lugar é o que o elimina.
     */
    static boolean rotaDoPc(String uri) {
        return ROTA_PC.equals(uri) || uri.startsWith(ROTA_PC + "/");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !rotaDoPc(requisicao.getRequestURI());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest requisicao, HttpServletResponse resposta,
            FilterChain corrente) throws ServletException, IOException {

        // Nenhuma rota liberada, nem OPTIONS: o agente não é navegador e não faz preflight.
        if (ComparadorDeToken.igual(tokenDoCabecalho(requisicao), tokenEsperado)) {
            corrente.doFilter(requisicao, resposta);
            return;
        }
        log.warn("Chamada do PC recusada em {} {} (token ausente ou inválido)",
                requisicao.getMethod(), requisicao.getRequestURI());
        resposta.setStatus(HttpStatus.UNAUTHORIZED.value());
        resposta.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resposta.setCharacterEncoding(StandardCharsets.UTF_8.name());
        // Letra por letra igual ao da HUD: uma mensagem diferente para "token da HUD em rota de
        // PC" seria um oráculo dizendo qual dos dois segredos o atacante acertou.
        resposta.getWriter().write("{\"erro\":\"token ausente ou invalido\"}");
    }

    /** Só o cabeçalho. Sem exceção de query string — ver o javadoc da classe. */
    private static String tokenDoCabecalho(HttpServletRequest requisicao) {
        String cabecalho = requisicao.getHeader(HttpHeaders.AUTHORIZATION);
        if (cabecalho != null && cabecalho.startsWith(PREFIXO)) {
            return cabecalho.substring(PREFIXO.length()).trim();
        }
        return null;
    }
}
