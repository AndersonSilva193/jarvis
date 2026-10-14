package dev.jarvis.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Testa a conversão de markdown para o HTML do Telegram. Função pura: sem Spring e sem rede.
 */
class MarkdownTelegramTest {

    @Nested
    @DisplayName("conversão")
    class Conversao {

        @Test
        @DisplayName("escapa < > e & do texto comum")
        void escapaTextoComum() {
            assertEquals("a &lt; b &amp;&amp; c &gt; d", MarkdownTelegram.paraHtml("a < b && c > d"));
        }

        @Test
        @DisplayName("negrito vira <b> e sempre fecha")
        void negrito() {
            assertEquals("ficou <b>pronto</b>, senhor", MarkdownTelegram.paraHtml("ficou **pronto**, senhor"));
            // Asterisco solto não é negrito e não pode abrir tag nenhuma.
            assertEquals("2 * 3 = 6", MarkdownTelegram.paraHtml("2 * 3 = 6"));
        }

        @Test
        @DisplayName("código embutido vira <code>, com o conteúdo escapado")
        void codigoEmbutido() {
            assertEquals("use <code>List&lt;String&gt;</code> aqui",
                    MarkdownTelegram.paraHtml("use `List<String>` aqui"));
        }

        @Test
        @DisplayName("bloco cercado vira <pre><code>, guardando a linguagem")
        void blocoDeCodigo() {
            String html = MarkdownTelegram.paraHtml("veja:\n```java\nif (a < b) {}\n```");

            assertEquals("veja:\n<pre><code class=\"language-java\">if (a &lt; b) {}</code></pre>", html);
        }

        @Test
        @DisplayName("cerca aberta e nunca fechada não vaza ``` na tela")
        void cercaSolta() {
            String html = MarkdownTelegram.paraHtml("olha:\n```\nmvn package");

            assertFalse(html.contains("```"), html);
            assertTrue(html.contains("<pre><code>mvn package</code></pre>"), html);
        }

        @Test
        @DisplayName("markdown dentro de código não é interpretado")
        void nadaDeNegritoDentroDeCodigo() {
            assertEquals("<code>**isso**</code>", MarkdownTelegram.paraHtml("`**isso**`"));
        }

        @Test
        @DisplayName("lista com - vira marcador em texto, porque o Telegram não tem <ul>")
        void listas() {
            String html = MarkdownTelegram.paraHtml("- site no ar\n- banco ok");

            assertEquals("• site no ar\n• banco ok", html);
        }

        @Test
        @DisplayName("texto nulo ou vazio devolve vazio em vez de estourar")
        void vazio() {
            assertEquals("", MarkdownTelegram.paraHtml(null));
            assertEquals("", MarkdownTelegram.paraHtml(""));
        }
    }

    @Nested
    @DisplayName("divisão em mensagens")
    class Divisao {

        @Test
        @DisplayName("texto curto sai em uma mensagem só")
        void textoCurto() {
            assertEquals(List.of("oi, senhor"), MarkdownTelegram.dividir("oi, senhor", 4000));
        }

        @Test
        @DisplayName("cada pedaço cabe no limite e nenhuma tag fica aberta")
        void respeitaOLimiteSemPartirTag() {
            StringBuilder longo = new StringBuilder();
            for (int i = 0; i < 400; i++) {
                longo.append("- item **").append(i).append("** com `código<>` no meio\n");
            }

            List<String> partes = MarkdownTelegram.dividir(longo.toString(), 400);

            assertTrue(partes.size() > 1, "o texto é longo demais para uma mensagem só");
            for (String parte : partes) {
                String html = MarkdownTelegram.paraHtml(parte);
                assertTrue(html.length() <= 400, "pedaço com " + html.length() + " caracteres");
                assertTagsFechadas(html);
            }
        }

        @Test
        @DisplayName("corte dentro de um bloco de código fecha e reabre a cerca")
        void cortaBlocoDeCodigo() {
            StringBuilder codigo = new StringBuilder("```java\n");
            for (int i = 0; i < 200; i++) {
                codigo.append("System.out.println(").append(i).append(");\n");
            }
            codigo.append("```");

            List<String> partes = MarkdownTelegram.dividir(codigo.toString(), 500);

            assertTrue(partes.size() > 1, "era para ter quebrado em várias mensagens");
            for (String parte : partes) {
                String html = MarkdownTelegram.paraHtml(parte);
                assertTrue(html.length() <= 500, "pedaço com " + html.length() + " caracteres");
                assertFalse(html.contains("```"), "a cerca não pode vazar como texto: " + html);
                assertTagsFechadas(html);
            }
        }

        @Test
        @DisplayName("uma linha gigante sem espaço ainda é quebrada, sem perder caractere")
        void linhaSemEspaco() {
            List<String> partes = MarkdownTelegram.dividir("a".repeat(5000), 200);

            for (String parte : partes) {
                assertTrue(MarkdownTelegram.paraHtml(parte).length() <= 200);
            }
            // Os pedaços de uma "palavra" grande demais viram linhas, então o remendo ganha \n:
            // o que não pode é sumir caractere no caminho.
            assertEquals("a".repeat(5000), String.join("", partes).replace("\n", ""));
        }
    }

    /** Conta aberturas e fechamentos de cada tag que a gente gera. */
    private static void assertTagsFechadas(String html) {
        for (String tag : List.of("b", "code", "pre")) {
            assertEquals(contar(html, "<" + tag), contar(html, "</" + tag + ">"),
                    "tag " + tag + " desbalanceada em: " + html);
        }
    }

    private static int contar(String html, String trecho) {
        int total = 0;
        for (int i = html.indexOf(trecho); i >= 0; i = html.indexOf(trecho, i + 1)) {
            total++;
        }
        return total;
    }
}
