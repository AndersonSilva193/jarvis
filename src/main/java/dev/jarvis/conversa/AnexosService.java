package dev.jarvis.conversa;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PreDestroy;
import dev.jarvis.ferramentas.ResultadoFerramenta.Artefato;

/** Cópias temporárias de artefatos produzidos por ferramentas, limitadas a 50 MB. */
@Service
public class AnexosService {
    public static final long LIMITE_ARQUIVO = 10 * 1024 * 1024;
    private static final long LIMITE_TOTAL = 50 * 1024 * 1024;
    private record Guardado(Anexo anexo, Path arquivo, long tamanho, Instant expira) { }
    public record Conteudo(Anexo anexo, byte[] bytes) { }
    private final Map<String, Guardado> arquivos = new HashMap<>();
    private final Clock relogio;
    private Path pasta;
    private long ocupado;

    public AnexosService() { this(Clock.systemUTC()); }
    public AnexosService(Clock relogio) { this.relogio = relogio; }

    public synchronized Anexo registrar(Artefato artefato) throws IOException {
        limparExpirados();
        Path origem = artefato.arquivo();
        if (!Files.isRegularFile(origem, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Artefato inválido");
        // A leitura limitada também protege contra um arquivo que cresce durante a cópia.
        byte[] bytes;
        try (var entrada = Files.newInputStream(origem, LinkOption.NOFOLLOW_LINKS)) {
            bytes = entrada.readNBytes((int) LIMITE_ARQUIVO + 1);
        }
        if (bytes.length > LIMITE_ARQUIVO || ocupado + bytes.length > LIMITE_TOTAL)
            throw new IOException("Limite de anexos atingido");
        if (pasta == null) pasta = Files.createTempDirectory("jarvis-anexos-");
        String id = UUID.randomUUID().toString();
        String nome = artefato.nome() == null ? "arquivo" : artefato.nome().replaceAll("[\\\\/\\r\\n\\p{Cntrl}]", "_");
        String mime = artefato.mime() == null ? "application/octet-stream" : artefato.mime();
        boolean imagem = java.util.Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(mime);
        Instant expira = relogio.instant().plus(Duration.ofHours(1));
        Anexo anexo = new Anexo(id, imagem ? "imagem" : "arquivo", nome,
                imagem ? mime : "application/octet-stream", "", expira.toString());
        Path destino = pasta.resolve(id);
        Files.write(destino, bytes);
        arquivos.put(id, new Guardado(anexo, destino, bytes.length, expira));
        ocupado += bytes.length;
        return anexo;
    }

    public Optional<Anexo> referencia(String nome, String url) {
        try {
            URI uri = URI.create(url);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) return Optional.empty();
            return Optional.of(new Anexo(UUID.randomUUID().toString(), "link", nome,
                    "", uri.toASCIIString(), null));
        } catch (RuntimeException e) { return Optional.empty(); }
    }

    public synchronized Optional<Conteudo> obter(String id) throws IOException {
        limparExpirados();
        Guardado guardado = arquivos.get(id);
        return guardado == null || !relogio.instant().isBefore(guardado.expira()) ? Optional.empty()
                : Optional.of(new Conteudo(guardado.anexo(), Files.readAllBytes(guardado.arquivo())));
    }

    @Scheduled(fixedRate = 60_000)
    public synchronized void limparExpirados() {
        var itens = arquivos.entrySet().iterator();
        while (itens.hasNext()) {
            Guardado guardado = itens.next().getValue();
            if (!relogio.instant().isBefore(guardado.expira())) {
                try {
                    Files.deleteIfExists(guardado.arquivo());
                    ocupado -= guardado.tamanho();
                    itens.remove();
                } catch (IOException ignorada) { /* A próxima limpeza tenta novamente. */ }
            }
        }
    }

    @PreDestroy
    public synchronized void fechar() {
        for (Guardado guardado : arquivos.values()) {
            try { Files.deleteIfExists(guardado.arquivo()); } catch (IOException ignorada) { }
        }
        arquivos.clear();
        ocupado = 0;
        if (pasta != null) {
            try { Files.deleteIfExists(pasta); } catch (IOException ignorada) { }
        }
    }
}
