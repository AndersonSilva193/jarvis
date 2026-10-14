package dev.jarvis.voz;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;

/**
 * Os MP3s já sintetizados, em disco.
 *
 * O Jarvis repete as mesmas frases todo dia ("Bom dia, senhor.", "A agenda de hoje está livre.").
 * Cada uma dessas é paga uma vez só: a chave é o sha-256 de voz + modelo + texto, então o arquivo
 * serve para sempre e trocar a voz no .env não devolve o áudio da voz antiga.
 */
@Component
public class CacheDeVoz {

    private static final Logger log = LoggerFactory.getLogger(CacheDeVoz.class);
    /** Volume do docker-compose. Uns 4 KB por segundo de fala: 200 MB dão ~14 horas de áudio. */
    private static final Path PADRAO = Path.of("/data/voz");
    private static final long TETO_BYTES = 200L * 1024 * 1024;

    private final Path diretorio;
    private final JarvisProperties props;
    private final boolean ligado;

    @Autowired
    public CacheDeVoz(JarvisProperties props) {
        this(props, PADRAO);
    }

    /** Só os testes usam outro diretório; em produção é sempre o volume /data/voz. */
    CacheDeVoz(JarvisProperties props, Path diretorio) {
        this.props = props;
        this.diretorio = diretorio;
        this.ligado = criarDiretorio(diretorio);
    }

    private static boolean criarDiretorio(Path diretorio) {
        try {
            Files.createDirectories(diretorio);
            return true;
        } catch (IOException e) {
            // Fora do Docker o /data pode não existir nem ser criável. Falar sem cache é melhor
            // do que não subir.
            log.warn("Sem cache de voz: não consegui usar {} ({})", diretorio, e.getMessage());
            return false;
        }
    }

    /**
     * Identificador do áudio deste texto nesta voz. É o mesmo valor que vai na URL, e é estável:
     * o mesmo texto sempre dá o mesmo id enquanto VOZ_ID e VOZ_MODELO não mudarem.
     */
    public String chave(String texto) {
        String vozId = props.voz() == null || props.voz().vozId() == null ? "" : props.voz().vozId();
        return sha256(vozId + "\n" + props.modeloDeVoz() + "\n" + texto);
    }

    /** Se já existe áudio para esta chave. Conta como uso, para não ser o próximo descartado. */
    public boolean existe(String chave) {
        if (!ligado || !Files.isRegularFile(arquivo(chave))) {
            return false;
        }
        try {
            Files.setLastModifiedTime(arquivo(chave), FileTime.from(Instant.now()));
        } catch (IOException e) {
            // Falhar em marcar o uso não invalida o acerto: o arquivo está lá.
        }
        return true;
    }

    /** O MP3 guardado, se existir. */
    public Optional<byte[]> buscar(String chave) {
        if (!ligado) {
            return Optional.empty();
        }
        Path arquivo = arquivo(chave);
        try {
            if (!Files.isRegularFile(arquivo)) {
                return Optional.empty();
            }
            byte[] conteudo = Files.readAllBytes(arquivo);
            Files.setLastModifiedTime(arquivo, FileTime.from(Instant.now()));
            return Optional.of(conteudo);
        } catch (IOException e) {
            log.warn("Não consegui ler o áudio {} do cache: {}", chave, e.getMessage());
            return Optional.empty();
        }
    }

    /** Guarda o MP3 e, se passou do teto, apaga os menos usados até caber. */
    public void guardar(String chave, byte[] audio) {
        if (!ligado) {
            return;
        }
        try {
            Files.write(arquivo(chave), audio);
            descartarExcedente();
        } catch (IOException e) {
            log.warn("Não consegui guardar o áudio {} no cache: {}", chave, e.getMessage());
        }
    }

    /** Apaga do menos usado para o mais usado até a pasta voltar a caber no teto. */
    private void descartarExcedente() throws IOException {
        List<Path> arquivos = new ArrayList<>();
        long total = 0;
        try (var lista = Files.list(diretorio)) {
            for (Path arquivo : lista.filter(Files::isRegularFile).toList()) {
                arquivos.add(arquivo);
                total += Files.size(arquivo);
            }
        }
        if (total <= TETO_BYTES) {
            return;
        }
        arquivos.sort(Comparator.comparing(CacheDeVoz::usadoEm));
        int apagados = 0;
        for (Path arquivo : arquivos) {
            if (total <= TETO_BYTES) {
                break;
            }
            long tamanho = Files.size(arquivo);
            if (Files.deleteIfExists(arquivo)) {
                total -= tamanho;
                apagados++;
            }
        }
        log.info("Cache de voz passou de {} MB: {} áudios antigos apagados", TETO_BYTES / 1024 / 1024, apagados);
    }

    private static FileTime usadoEm(Path arquivo) {
        try {
            return Files.getLastModifiedTime(arquivo);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path arquivo(String chave) {
        return diretorio.resolve(chave + ".mp3");
    }

    private static String sha256(String valor) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 deveria existir em qualquer JVM", e);
        }
    }
}
