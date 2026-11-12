package dev.jarvis;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * Todo volume nomeado sob {@code /data} precisa existir na imagem, com dono {@code jarvis}, antes
 * do {@code USER jarvis}: o Docker copia o dono da pasta da imagem ao criar o volume. Pasta que
 * falta nasce do root, e o Jarvis (uid 1001) não grava nela — foi assim que o livro financeiro
 * falhou com "erro de gravação em /data/jarvis/diario".
 */
class DockerfileTest {

    @Test
    void criaTodasAsPastasDosVolumes() throws Exception {
        String compose = Files.readString(Path.of("docker-compose.yml"));
        // As linhas que terminam em "\" continuam na de baixo: o RUN vira uma linha só.
        String dockerfile = Files.readString(Path.of("Dockerfile")).replaceAll("\\\\\\r?\\n", " ");
        List<String> criadas = new ArrayList<>();
        for (String linha : dockerfile.split("\\R")) {
            int mkdir = linha.indexOf("mkdir -p");
            if (mkdir < 0) continue;
            String comando = linha.substring(mkdir + "mkdir -p".length()).split("&&")[0];
            criadas.addAll(List.of(comando.trim().split("\\s+")));
        }
        Matcher volume = Pattern.compile("(?m)^\\s*-\\s*[a-z-]+:(/data/[A-Za-z0-9/_-]+)\\s*$").matcher(compose);
        List<String> faltando = new ArrayList<>();
        int vistos = 0;
        while (volume.find()) {
            vistos++;
            if (!criadas.contains(volume.group(1))) faltando.add(volume.group(1));
        }
        assertTrue(vistos >= 10, "achei só " + vistos + " volumes em /data no compose");
        assertTrue(faltando.isEmpty(), "o Dockerfile não cria: " + faltando);
        assertTrue(dockerfile.contains("chown -R jarvis:jarvis /data"), "o chown precisa cobrir /data inteiro");
    }
}
