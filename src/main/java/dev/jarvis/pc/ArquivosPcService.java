package dev.jarvis.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import dev.jarvis.config.JarvisProperties;

/** Recebe arquivos do agente apenas para comandos de criação ainda vivos. */
@Service
public class ArquivosPcService {
    public static final int LIMITE = 8 * 1024 * 1024;
    private record Guardado(Path caminho, Instant expira) { }
    private final Map<String, Guardado> arquivos = new HashMap<>();
    private final PcService pc;
    private final Clock relogio;
    private final int limiteEdicao;

    @org.springframework.beans.factory.annotation.Autowired
    public ArquivosPcService(PcService pc, JarvisProperties props) {
        this(pc, Clock.systemUTC(), props.pc() == null ? 1024 * 1024
                : props.pc().edicaoMaxBytesResultado());
    }
    public ArquivosPcService(PcService pc) { this(pc, Clock.systemUTC()); }
    ArquivosPcService(PcService pc, Clock relogio) { this(pc, relogio, 1024 * 1024); }
    private ArquivosPcService(PcService pc, Clock relogio, int limiteEdicao) {
        this.pc = pc; this.relogio = relogio;
        this.limiteEdicao = Math.clamp(limiteEdicao, 1, LIMITE);
    }

    public synchronized boolean guardar(String id, byte[] bytes) throws IOException {
        limpar();
        if (bytes == null || bytes.length == 0 || bytes.length > LIMITE)
            return false;
        if (pc.estadoE2(id).filter(e -> java.util.List.of("aplicar_edicao", "aplicar_desfazer")
                .contains(e.acao())).isPresent() && bytes.length > limiteEdicao) return false;
        Optional<Path> persistente = pc.caminhoArquivoE2(id);
        if (persistente.isPresent()) {
            Path destino = persistente.orElseThrow();
            if (Files.isRegularFile(destino)) return java.util.Arrays.equals(bytes, Files.readAllBytes(destino));
            if (!pc.aguardandoArquivo(id)) return false;
            Files.createDirectories(destino.getParent());
            Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
            Files.write(temporario, bytes);
            Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            return true;
        }
        if (arquivos.containsKey(id)) return java.util.Arrays.equals(bytes, Files.readAllBytes(arquivos.get(id).caminho()));
        if (!pc.aguardandoArquivo(id)) return false;
        Path caminho = Files.createTempFile("jarvis-arquivo-pc-", ".tmp");
        try { Files.write(caminho, bytes); }
        catch (IOException e) { Files.deleteIfExists(caminho); throw e; }
        arquivos.put(id, new Guardado(caminho, relogio.instant().plus(Duration.ofMinutes(5))));
        return true;
    }

    public synchronized Optional<Path> obter(String id) {
        limpar();
        Optional<Path> persistente = pc.caminhoArquivoE2(id);
        if (persistente.isPresent() && Files.isRegularFile(persistente.orElseThrow()))
            return persistente;
        Guardado guardado = arquivos.get(id);
        if (guardado == null) return Optional.empty();
        arquivos.put(id, new Guardado(guardado.caminho(), relogio.instant().plus(Duration.ofHours(1))));
        return Optional.of(guardado.caminho());
    }

    @Scheduled(fixedRate = 60_000)
    public synchronized void limpar() {
        var iterador = arquivos.entrySet().iterator();
        while (iterador.hasNext()) {
            Guardado guardado = iterador.next().getValue();
            if (!relogio.instant().isBefore(guardado.expira())) {
                try { Files.deleteIfExists(guardado.caminho()); iterador.remove(); }
                catch (IOException ignorada) { }
            }
        }
    }

    @jakarta.annotation.PreDestroy
    public synchronized void fechar() {
        for (Guardado guardado : arquivos.values())
            try { Files.deleteIfExists(guardado.caminho()); } catch (IOException ignorada) { }
        arquivos.clear();
    }
}
