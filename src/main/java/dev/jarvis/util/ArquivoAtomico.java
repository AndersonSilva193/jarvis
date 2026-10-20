package dev.jarvis.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * A troca atômica de um arquivo de texto em disco, extraída do que os lembretes já faziam.
 *
 * Grava num temporário na mesma pasta e troca com {@code ATOMIC_MOVE}: um desligamento no meio da
 * escrita deixa o arquivo antigo inteiro, nunca meio JSON. A versão anterior fica num {@code .bak}
 * ao lado, copiada <b>antes</b> da troca — depois do move o arquivo antigo já não existe.
 *
 * Mesma pasta não é detalhe: {@code ATOMIC_MOVE} só é garantido dentro do mesmo sistema de
 * arquivos, e um temporário em {@code /tmp} com o destino num volume Docker cairia numa cópia
 * comum, que é justamente o que se quer evitar.
 */
public final class ArquivoAtomico {

    private ArquivoAtomico() {
    }

    /** O {@code .bak} que acompanha um arquivo: o mesmo nome com o sufixo. */
    public static Path backupDe(Path arquivo) {
        return arquivo.resolveSibling(arquivo.getFileName() + ".bak");
    }

    /**
     * Grava o conteúdo em UTF-8, deixando a versão anterior no {@code .bak}. Cria a pasta se
     * preciso e lança {@link IOException} para quem chamou decidir o que logar.
     */
    public static void gravar(Path arquivo, String conteudo) throws IOException {
        Path destino = arquivo.toAbsolutePath().normalize();
        Path pasta = destino.getParent();
        if (pasta == null) {
            throw new IOException("arquivo sem pasta: " + destino);
        }
        Files.createDirectories(pasta);
        Path temporario = Files.createTempFile(pasta, ".jarvis-", ".tmp");
        try {
            Files.writeString(temporario, conteudo, StandardCharsets.UTF_8);
            if (Files.exists(destino)) {
                Files.copy(destino, backupDe(destino), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(temporario, destino, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporario);
        }
    }

    /** Lê o arquivo em UTF-8 recusando o que passar do teto de sanidade. */
    public static String ler(Path arquivo, long maxBytes) throws IOException {
        if (Files.size(arquivo) > maxBytes) {
            throw new IOException("arquivo acima do limite de " + maxBytes + " bytes");
        }
        return Files.readString(arquivo, StandardCharsets.UTF_8);
    }

    /** Apaga o arquivo e o {@code .bak} dele. Usado quando o dado tem de sair do disco. */
    public static void apagar(Path arquivo) throws IOException {
        Path destino = arquivo.toAbsolutePath().normalize();
        Files.deleteIfExists(destino);
        Files.deleteIfExists(backupDe(destino));
    }
}
