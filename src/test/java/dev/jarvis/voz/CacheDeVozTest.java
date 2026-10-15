package dev.jarvis.voz;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;

/** O cache é o que impede a mesma frase de ser paga duas vezes, então a chave tem de ser estável. */
class CacheDeVozTest {

    private static JarvisProperties props(String vozId, String modelo) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null, null, null,
                new JarvisProperties.Voz("elevenlabs", "chave", vozId, modelo, 5), null, null, null);
    }

    @Test
    @DisplayName("a chave do mesmo texto é sempre a mesma, e muda com a voz ou com o modelo")
    void chaveEstavel(@TempDir Path pasta) {
        var cache = new CacheDeVoz(props("voz-1", "eleven_flash_v2_5"), pasta);

        String primeira = cache.chave("Bom dia, senhor.");
        assertEquals(primeira, cache.chave("Bom dia, senhor."));
        assertEquals(64, primeira.length(), "o id vai na URL e o filtro espera 64 hex");
        assertTrue(primeira.matches("[a-f0-9]{64}"));

        assertNotEquals(primeira, cache.chave("Boa noite, senhor."));
        assertNotEquals(primeira, new CacheDeVoz(props("voz-2", "eleven_flash_v2_5"), pasta)
                .chave("Bom dia, senhor."));
        assertNotEquals(primeira, new CacheDeVoz(props("voz-1", "eleven_turbo_v2_5"), pasta)
                .chave("Bom dia, senhor."));
    }

    @Test
    @DisplayName("guarda e devolve o mesmo áudio")
    void guardaEDevolve(@TempDir Path pasta) {
        var cache = new CacheDeVoz(props("voz-1", "eleven_flash_v2_5"), pasta);
        byte[] audio = "mp3 de mentira".getBytes();
        String chave = cache.chave("A agenda de hoje está livre.");

        assertFalse(cache.existe(chave));
        cache.guardar(chave, audio);

        assertTrue(cache.existe(chave));
        assertArrayEquals(audio, cache.buscar(chave).orElseThrow());
    }

    @Test
    @DisplayName("passando do teto, apaga primeiro o áudio menos usado")
    void descartaOMenosUsado(@TempDir Path pasta) throws IOException {
        var cache = new CacheDeVoz(props("voz-1", "eleven_flash_v2_5"), pasta);
        // 201 MB em arquivos de 1 MB: a gravação seguinte tem de derrubar os mais velhos.
        byte[] umMega = new byte[1024 * 1024];
        for (int i = 0; i < 201; i++) {
            Path arquivo = pasta.resolve("%064x".formatted(i) + ".mp3");
            Files.write(arquivo, umMega);
            // O mais antigo é o de índice 0, e assim por diante.
            Files.setLastModifiedTime(arquivo,
                    FileTime.from(Instant.now().minus(201 - i, ChronoUnit.MINUTES)));
        }

        cache.guardar(cache.chave("recém-chegada"), umMega);

        long total;
        try (var lista = Files.list(pasta)) {
            total = lista.mapToLong(arquivo -> arquivo.toFile().length()).sum();
        }
        assertTrue(total <= 200L * 1024 * 1024, "o cache deveria caber no teto, mas tem " + total);
        assertTrue(cache.existe(cache.chave("recém-chegada")), "a recém-gravada não é descartada");
        assertFalse(Files.exists(pasta.resolve("%064x".formatted(0) + ".mp3")),
                "a mais antiga deveria ter sido a primeira a sair");
    }

    @Test
    @DisplayName("sem diretório gravável o Jarvis fala assim mesmo, só sem cache")
    void semDiretorioNaoQuebra(@TempDir Path pasta) throws IOException {
        // Um arquivo onde deveria haver uma pasta: createDirectories falha e o cache se desliga.
        Path ocupado = pasta.resolve("ocupado");
        Files.writeString(ocupado, "não sou uma pasta");
        var cache = new CacheDeVoz(props("voz-1", "eleven_flash_v2_5"), ocupado);

        cache.guardar(cache.chave("oi"), new byte[] { 1, 2, 3 });

        assertFalse(cache.existe(cache.chave("oi")));
        assertTrue(cache.buscar(cache.chave("oi")).isEmpty());
    }
}
