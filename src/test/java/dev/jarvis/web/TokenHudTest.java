package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;

/** Testa a comparação do token e a leitura da lista de origens. Sem Spring e sem rede. */
class TokenHudTest {

    private static final String TOKEN = "9f2c4b7a1e8d3056aa14bb27cc38dd49ee5af60b71c82d93e4f5061728394a5b";

    @Test
    @DisplayName("aceita o token certo e recusa o errado")
    void comparaToken() {
        assertTrue(TokenHudFiltro.tokenValido(TOKEN, TOKEN));
        assertFalse(TokenHudFiltro.tokenValido(TOKEN.toUpperCase(), TOKEN));
        assertFalse(TokenHudFiltro.tokenValido(TOKEN.substring(0, TOKEN.length() - 1) + "c", TOKEN));
    }

    @Test
    @DisplayName("tamanho diferente não passa (e nem influencia a comparação)")
    void tamanhoDiferente() {
        assertFalse(TokenHudFiltro.tokenValido(TOKEN + "extra", TOKEN));
        assertFalse(TokenHudFiltro.tokenValido("x", TOKEN));
    }

    @Test
    @DisplayName("token ausente, vazio ou em branco não passa")
    void tokenAusente() {
        assertFalse(TokenHudFiltro.tokenValido(null, TOKEN));
        assertFalse(TokenHudFiltro.tokenValido("", TOKEN));
        assertFalse(TokenHudFiltro.tokenValido("   ", TOKEN));
    }

    @Test
    @DisplayName("HUD_TOKEN não configurado recusa tudo, inclusive um pedido vazio")
    void semTokenConfiguradoRecusaTudo() {
        assertFalse(TokenHudFiltro.tokenValido(TOKEN, null));
        assertFalse(TokenHudFiltro.tokenValido(TOKEN, ""));
        assertFalse(TokenHudFiltro.tokenValido(TOKEN, "   "));
        assertFalse(TokenHudFiltro.tokenValido("", ""));
        assertFalse(TokenHudFiltro.tokenValido(null, null));
    }

    @Test
    @DisplayName("origens do CORS: separa por vírgula, tira espaço e ignora pedaço vazio")
    void leOrigens() {
        assertEquals(List.of("https://jarvis-hud.vercel.app", "http://localhost:4200"),
                comOrigens("https://jarvis-hud.vercel.app, http://localhost:4200"));
        assertEquals(List.of("http://localhost:4200"), comOrigens(" http://localhost:4200 ,, "));
        assertEquals(List.of(), comOrigens(""));
        assertEquals(List.of(), comOrigens(null));
    }

    private static List<String> comOrigens(String config) {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                new JarvisProperties.Hud("token", config));
        return props.origensDaHud();
    }
}
