package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Testa a leitura dos metadados Open Graph e a decisão de incorporar, com um HTML de exemplo em
 * src/test/resources. Sem rede: o download é testado pela trava de SSRF, aqui é só a leitura.
 */
class PaginaServiceTest {

    private static final List<String> ORIGENS =
            List.of("https://jarvis-hud-gamma.vercel.app", "http://localhost:4200");

    private static String exemplo() throws IOException {
        try (InputStream in = PaginaServiceTest.class.getResourceAsStream("/pagina-og.html")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("lê os quatro metadados og, em qualquer ordem e com qualquer aspa")
    void leOsMetadados() throws Exception {
        PaginaService.Previa previa =
                PaginaService.interpretar(exemplo(), "https://exemplo.com/noticia", true);

        assertEquals("Reforma tributária aprovada no Senado", previa.titulo(),
                "o og:title vem com content antes de property, que é comum na vida real");
        assertEquals("O texto passou por 61 votos a 10 & segue para sanção presidencial.",
                previa.descricao());
        assertEquals("https://exemplo.com/imagens/senado.jpg", previa.imagem(),
                "esse vem sem aspa nenhuma");
        assertEquals("Exemplo Notícias", previa.site());
        assertEquals("https://exemplo.com/noticia", previa.url());
        assertTrue(previa.incorporavel());
    }

    @Test
    @DisplayName("og do corpo da página é ignorado: só a cabeça conta")
    void ignoraOgDoCorpo() throws Exception {
        PaginaService.Previa previa =
                PaginaService.interpretar(exemplo(), "https://exemplo.com/noticia", true);

        assertFalse(previa.titulo().contains("no corpo"), previa.titulo());
    }

    @Test
    @DisplayName("sem og:title, o title do documento serve de reserva")
    void tituloDeReserva() {
        String html = "<html><head><title>Só o title aqui</title></head><body>oi</body></html>";

        assertEquals("Só o title aqui",
                PaginaService.interpretar(html, "https://exemplo.com/", true).titulo());
    }

    @Test
    @DisplayName("sem título nenhum, a própria URL vira o título do card")
    void semTituloUsaAUrl() {
        PaginaService.Previa previa =
                PaginaService.interpretar("<html><body>nada</body></html>", "https://exemplo.com/x", true);

        assertEquals("https://exemplo.com/x", previa.titulo(),
                "card sem título não passa pelas guardas do CardTela");
        assertEquals("", previa.descricao());
    }

    @Test
    @DisplayName("X-Frame-Options barra a incorporação")
    void xFrameOptions() {
        assertFalse(PaginaService.incorporavel("DENY", "", ORIGENS));
        assertFalse(PaginaService.incorporavel("sameorigin", "", ORIGENS));
        assertFalse(PaginaService.incorporavel("ALLOW-FROM https://outro.com", "", ORIGENS));
        assertTrue(PaginaService.incorporavel("", "", ORIGENS));
        assertTrue(PaginaService.incorporavel(null, null, ORIGENS));
    }

    @Test
    @DisplayName("frame-ancestors decide quando o X-Frame-Options não diz nada")
    void frameAncestors() {
        assertFalse(PaginaService.incorporavel("",
                "default-src 'self'; frame-ancestors 'none'", ORIGENS));
        assertTrue(PaginaService.incorporavel("",
                "default-src 'self'; frame-ancestors *", ORIGENS));
        assertTrue(PaginaService.incorporavel("",
                "frame-ancestors https://jarvis-hud-gamma.vercel.app", ORIGENS),
                "a HUD está na lista permitida, então o navegador vai renderizar");
        assertFalse(PaginaService.incorporavel("",
                "frame-ancestors https://outro-site.com", ORIGENS),
                "marcar incorporável o que o navegador vai recusar renderiza um quadro em branco");
        assertTrue(PaginaService.incorporavel("",
                "default-src 'self'; script-src 'none'", ORIGENS),
                "CSP sem frame-ancestors não fala de incorporação");
    }
}
