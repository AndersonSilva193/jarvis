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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Testa só a leitura da resposta da YouTube Data API, com um JSON de exemplo em src/test/resources:
 * sem Spring, sem rede e sem gastar cota.
 */
class YoutubeServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode exemplo() throws IOException {
        try (InputStream in = YoutubeServiceTest.class.getResourceAsStream("/youtube-busca.json")) {
            return JSON.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("lê os vídeos na ordem em que a API devolveu")
    void interpretaNaOrdem() throws Exception {
        List<YoutubeService.Video> videos = YoutubeService.interpretar(exemplo());

        assertEquals(List.of("jfKfPfyJRdk", "5qap5aO4i9A", "DWcJFNfaw9c", "n61ULEU7CO0"),
                videos.stream().map(YoutubeService.Video::videoId).toList(),
                "a ordem é a relevância que a API calculou; o primeiro é o que vai tocar");
    }

    @Test
    @DisplayName("id fora do formato de 11 caracteres é descartado")
    void descartaIdInvalido() throws Exception {
        List<YoutubeService.Video> videos = YoutubeService.interpretar(exemplo());

        assertTrue(videos.stream().noneMatch(v -> v.videoId().equals("CURTO12345")),
                "esse id iria direto para um iframe na HUD: o que não reconhecemos não entra");
        assertTrue(videos.stream().allMatch(v -> v.videoId().length() == 11));
    }

    @Test
    @DisplayName("resultado que não é vídeo fica de fora")
    void descartaOQueNaoEhVideo() throws Exception {
        List<YoutubeService.Video> videos = YoutubeService.interpretar(exemplo());

        assertFalse(videos.stream().anyMatch(v -> v.titulo().contains("Um canal")));
    }

    @Test
    @DisplayName("título, canal e miniatura vêm do snippet, com a miniatura média na frente")
    void mapeiaOsCampos() throws Exception {
        YoutubeService.Video primeiro = YoutubeService.interpretar(exemplo()).getFirst();

        assertEquals("lofi hip hop radio - beats para estudar e relaxar", primeiro.titulo());
        assertEquals("Lofi Girl", primeiro.canal());
        assertEquals("https://i.ytimg.com/vi/jfKfPfyJRdk/mqdefault.jpg", primeiro.miniatura());
    }

    @Test
    @DisplayName("sem a miniatura média, cai para outro tamanho em vez de ficar vazia")
    void miniaturaDeReserva() throws Exception {
        YoutubeService.Video semMedia = YoutubeService.interpretar(exemplo()).stream()
                .filter(v -> v.videoId().equals("DWcJFNfaw9c"))
                .findFirst()
                .orElseThrow();

        assertEquals("https://i.ytimg.com/vi/DWcJFNfaw9c/default.jpg", semMedia.miniatura());
    }

    @Test
    @DisplayName("resposta sem items devolve lista vazia, sem estourar")
    void respostaSemItens() {
        assertEquals(List.of(), YoutubeService.interpretar(JSON.createObjectNode()));
        assertEquals(List.of(), YoutubeService.interpretar(null));
        assertEquals(List.of(),
                YoutubeService.interpretar(JSON.createObjectNode().put("items", "nada disso")));
    }

    @Test
    @DisplayName("a chave do cache ignora acento, caixa e espaço sobrando")
    void chaveNormalizada() {
        assertEquals(YoutubeService.chave("lo-fi para estudar", "musica"),
                YoutubeService.chave("Lo-Fi  Para Estudar", "musica"));
        assertEquals(YoutubeService.chave("musica classica", "musica"),
                YoutubeService.chave("Música Clássica", "musica"));

        assertFalse(YoutubeService.chave("lo-fi", "musica").equals(YoutubeService.chave("lo-fi", "video")),
                "o modo muda a busca na API, então não pode compartilhar entrada de cache");
    }
}
