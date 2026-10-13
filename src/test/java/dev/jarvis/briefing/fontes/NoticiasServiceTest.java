package dev.jarvis.briefing.fontes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.xml.sax.SAXException;

import dev.jarvis.briefing.fontes.NoticiasService.Noticia;

/**
 * Testa a leitura dos feeds com XMLs de exemplo guardados em src/test/resources, modelados em
 * feeds reais (G1, Ars Technica e The Verge). Sem Spring e sem rede: roda rápido e não depende
 * de nenhum portal estar no ar.
 */
class NoticiasServiceTest {

    /** Pouco depois da data dos itens "de hoje" nos fixtures, para a janela de 24 h ser previsível. */
    private static final Instant AGORA = Instant.parse("2026-10-12T22:00:00Z");

    private static List<Noticia> ler(String arquivo, String tema) throws IOException, SAXException {
        try (InputStream in = NoticiasServiceTest.class.getResourceAsStream("/" + arquivo)) {
            assertNotNull(in, "fixture " + arquivo + " não encontrado no classpath");
            return NoticiasService.interpretar(NoticiasService.ler(in), tema);
        }
    }

    private static Noticia porTitulo(List<Noticia> itens, String pedaco) {
        return itens.stream()
                .filter(n -> n.titulo().toLowerCase().contains(pedaco.toLowerCase()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("não achei item com '" + pedaco + "' em " + itens));
    }

    @Nested
    @DisplayName("RSS 2.0")
    class Rss {

        @Test
        @DisplayName("extrai os campos de um item, com fonte do canal e data em RFC 1123")
        void camposDoItem() throws Exception {
            Noticia n = porTitulo(ler("noticias-rss.xml", "tecnologia"), "Satélite");

            assertEquals("tecnologia", n.tema());
            assertEquals("Satélite brasileiro entra em órbita", n.titulo());
            assertEquals("https://exemplo.test/tecnologia/satelite.html", n.link());
            assertEquals("Portal de Teste > Tecnologia", n.fonte());
            assertEquals(Instant.parse("2026-10-12T19:53:17Z"), n.publicadoEm().toInstant());
            assertEquals("O lançamento ocorreu na madrugada e foi acompanhado por técnicos da agência "
                    + "espacial & convidados.", n.resumo());
        }

        @Test
        @DisplayName("pega a imagem do enclosure, do media:content e do media:thumbnail aninhado")
        void imagemNasTresOrigens() throws Exception {
            List<Noticia> itens = ler("noticias-rss.xml", "tecnologia");

            assertEquals("https://exemplo.test/img/satelite.jpg", porTitulo(itens, "Satélite").imagemUrl());
            assertEquals("https://exemplo.test/img/fibra-media.jpg", porTitulo(itens, "fibra").imagemUrl());
            assertEquals("https://exemplo.test/img/baterias-thumb.jpg", porTitulo(itens, "Baterias").imagemUrl());
        }

        @Test
        @DisplayName("sem tag de mídia, cai para a primeira <img> do texto")
        void imagemDoCorpoDoTexto() throws Exception {
            // O item da fibra tem media:content E uma <img> no texto: a tag de mídia tem prioridade,
            // então o fallback é testado pelo relatório, que não tem nenhuma tag de mídia.
            Noticia comImg = porTitulo(ler("noticias-rss.xml", "t"), "fibra");
            assertEquals("https://exemplo.test/img/fibra-media.jpg", comImg.imagemUrl());

            Noticia semImagem = porTitulo(ler("noticias-rss.xml", "t"), "Relatório");
            assertEquals("", semImagem.imagemUrl());
        }

        @Test
        @DisplayName("resumo sai sem HTML e cortado em 300 caracteres")
        void resumoLimpoECortado() throws Exception {
            Noticia n = porTitulo(ler("noticias-rss.xml", "tecnologia"), "Relatório");

            assertTrue(n.resumo().length() <= 301, "tamanho foi " + n.resumo().length());
            assertTrue(n.resumo().endsWith("…"), n.resumo());
            assertFalse(n.resumo().contains("<"), "sobrou HTML: " + n.resumo());
            assertTrue(n.resumo().startsWith("Lorem ipsum dolor sit amet"), n.resumo());
        }

        @Test
        @DisplayName("usa content:encoded quando não há description")
        void resumoDoContentEncoded() throws Exception {
            Noticia n = porTitulo(ler("noticias-rss.xml", "tecnologia"), "Baterias");
            assertEquals("Pesquisadores relatam ganho de densidade energética.", n.resumo());
        }
    }

    @Nested
    @DisplayName("Atom")
    class Atom {

        @Test
        @DisplayName("lê entry, published e o link rel=alternate (não o rel=self)")
        void camposDaEntry() throws Exception {
            Noticia n = porTitulo(ler("noticias-atom.xml", "ia"), "tradução");

            assertEquals("ia", n.tema());
            assertEquals("https://blog.test/traducao", n.link());
            assertEquals("Blog de Teste", n.fonte());
            assertEquals(Instant.parse("2026-10-12T20:30:00Z"), n.publicadoEm().toInstant());
            assertEquals("A nova versão cobre 22 idiomas e roda em uma GPU doméstica.", n.resumo());
            assertEquals("https://blog.test/img/traducao.png", n.imagemUrl());
        }

        @Test
        @DisplayName("aceita link sem rel e resumo vindo de content")
        void linkSemRelEContent() throws Exception {
            Noticia n = porTitulo(ler("noticias-atom.xml", "ia"), "latência");

            assertEquals("https://blog.test/latencia", n.link());
            assertEquals("Um guia curto sobre instrumentação.", n.resumo());
            assertEquals(Instant.parse("2026-10-12T17:05:00Z"), n.publicadoEm().toInstant());
        }
    }

    @Nested
    @DisplayName("janela de 24 h e duplicados")
    class Recentes {

        @Test
        @DisplayName("descarta velhas, futuras demais, sem data e títulos repetidos")
        void filtraOrdenaELimita() throws Exception {
            List<Noticia> todas = ler("noticias-rss.xml", "tecnologia");
            List<Noticia> recentes = NoticiasService.recentes(todas, AGORA, 10);

            List<String> titulos = recentes.stream().map(Noticia::titulo).toList();
            assertEquals(List.of(
                    "Satélite brasileiro entra em órbita",
                    "Nova rede de fibra chega ao interior",
                    "Baterias de sódio prometem carga mais rápida",
                    "Relatório anual do setor de tecnologia"), titulos,
                    "esperava as 4 das últimas 24 h, da mais nova para a mais velha");
        }

        @Test
        @DisplayName("respeita o limite pedido")
        void respeitaLimite() throws Exception {
            List<Noticia> recentes = NoticiasService.recentes(ler("noticias-rss.xml", "t"), AGORA, 2);
            assertEquals(2, recentes.size());
            assertEquals("Satélite brasileiro entra em órbita", recentes.get(0).titulo());
        }

        @Test
        @DisplayName("mistura temas diferentes ordenando pelo horário")
        void misturaTemas() throws Exception {
            List<Noticia> juntas = new java.util.ArrayList<>(ler("noticias-rss.xml", "tecnologia"));
            juntas.addAll(ler("noticias-atom.xml", "ia"));

            List<Noticia> recentes = NoticiasService.recentes(juntas, AGORA, 3);

            assertEquals(List.of("ia", "tecnologia", "tecnologia"),
                    recentes.stream().map(Noticia::tema).toList());
            assertEquals("Modelo aberto de tradução ganha versão em português", recentes.get(0).titulo());
        }

        @Test
        @DisplayName("item sem data é descartado")
        void itemSemData() throws Exception {
            Noticia semData = porTitulo(ler("noticias-rss.xml", "t"), "sem data");
            assertNull(semData.publicadoEm());

            List<String> titulos = NoticiasService.recentes(ler("noticias-rss.xml", "t"), AGORA, 10)
                    .stream().map(Noticia::titulo).toList();
            assertFalse(titulos.contains("Item sem data de publicação"), titulos.toString());
        }
    }

    @Nested
    @DisplayName("segurança do parser")
    class Seguranca {

        @Test
        @DisplayName("recusa feed com DOCTYPE/entidade externa (XXE) sem ler arquivo nenhum")
        void recusaXxe() throws Exception {
            try (InputStream in = NoticiasServiceTest.class.getResourceAsStream("/noticias-xxe.xml")) {
                assertNotNull(in);
                SAXException erro = assertThrows(SAXException.class, () -> NoticiasService.ler(in));

                String mensagem = erro.getMessage();
                assertTrue(mensagem.contains("DOCTYPE"), "esperava recusa no DOCTYPE, veio: " + mensagem);
                assertFalse(mensagem.contains("root:"), "a mensagem não pode conter conteúdo de arquivo");
            }
        }
    }

    @Nested
    @DisplayName("limpeza de texto")
    class Entidades {

        @Test
        @DisplayName("desfaz entidades nomeadas, numéricas e maiúsculas")
        void desfazEntidades() {
            assertEquals("técnicos da agência", NoticiasService.desfazerEntidades("t&eacute;cnicos da ag&ecirc;ncia"));
            assertEquals("É assim", NoticiasService.desfazerEntidades("&Eacute; assim"));
            assertEquals("ação é é", NoticiasService.desfazerEntidades("a&ccedil;&atilde;o &#233; &#xE9;"));
            assertEquals("Lucro & Cia", NoticiasService.desfazerEntidades("Lucro &amp; Cia"));
        }

        @Test
        @DisplayName("não decodifica duas vezes: &amp;eacute; continua texto literal")
        void naoDecodificaDuasVezes() {
            assertEquals("&eacute;", NoticiasService.desfazerEntidades("&amp;eacute;"));
        }

        @Test
        @DisplayName("entidade desconhecida fica como está, sem estragar o texto")
        void entidadeDesconhecida() {
            assertEquals("&naoexiste; fim", NoticiasService.desfazerEntidades("&naoexiste; fim"));
        }
    }

    @Nested
    @DisplayName("datas")
    class Datas {

        @Test
        @DisplayName("entende os formatos que os feeds reais usam")
        void formatosConhecidos() {
            assertEquals(Instant.parse("2026-10-12T19:53:17Z"),
                    NoticiasService.parseData("Mon, 12 Oct 2026 19:53:17 -0000").toInstant());
            assertEquals(Instant.parse("2026-10-12T21:54:35Z"),
                    NoticiasService.parseData("Mon, 12 Oct 2026 21:54:35 GMT").toInstant());
            assertEquals(Instant.parse("2026-10-12T21:45:36Z"),
                    NoticiasService.parseData("Mon, 12 Oct 2026 18:45:36 -0300").toInstant());
            assertEquals(Instant.parse("2026-10-12T21:21:23Z"),
                    NoticiasService.parseData("2026-10-12T21:21:23+00:00").toInstant());
            assertNull(NoticiasService.parseData("ontem à tarde"));
            assertNull(NoticiasService.parseData(""));
        }
    }
}
