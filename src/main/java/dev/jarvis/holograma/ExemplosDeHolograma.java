package dev.jarvis.holograma;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Os objetos de referência em {@code src/main/resources/holograma/exemplos}, escritos à mão: são
 * a régua de qualidade do que o modelo vai gerar e o que a HUD usa para ser testada sem gastar
 * modelo.
 *
 * <p>O nome passa por {@link #NOME} antes de virar caminho de recurso — sem ponto nem barra, não
 * há como sair da pasta.
 */
@Component
public class ExemplosDeHolograma {

    static final Pattern NOME = Pattern.compile("[a-z0-9_]{1,40}");
    private static final String PASTA = "holograma/exemplos/";

    /** As linhas do exemplo, ou vazio quando o nome não existe. */
    public Optional<List<String>> linhas(String nome) {
        if (nome == null || !NOME.matcher(nome).matches()) {
            return Optional.empty();
        }
        InputStream entrada = ExemplosDeHolograma.class.getClassLoader()
                .getResourceAsStream(PASTA + nome + ".ndjson");
        if (entrada == null) {
            return Optional.empty();
        }
        try (BufferedReader leitor = new BufferedReader(new InputStreamReader(entrada, StandardCharsets.UTF_8))) {
            return Optional.of(leitor.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException("Exemplo de holograma ilegível: " + nome, e);
        }
    }
}
