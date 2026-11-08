package dev.jarvis.agente;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.json.JsonMapper;

/** Freio global da autonomia. Conversa explícita e avisos informativos não passam por ele. */
@Component
public class Freio {
    private static final Logger log = LoggerFactory.getLogger(Freio.class);
    public enum Estado { ATIVO, PAUSADO }
    public record Retrato(Estado estado, Instant desde, String canal) { }
    @FunctionalInterface public interface Escrita<T> { T executar() throws IOException; }
    private final JarvisProperties props;
    private final MedidorDeCusto medidor;
    private final AvisosProativos avisos;
    private final FalasService falas;
    private final JsonMapper json;
    private final Clock relogio;
    private final Path arquivo;
    private final Object trava = new Object();
    private final List<Runnable> ouvintes = new CopyOnWriteArrayList<>();
    private final List<Runnable> retomadas = new CopyOnWriteArrayList<>();
    private Estado estado = Estado.ATIVO;
    private Instant desde;
    private String canal = "servidor";
    private String diaAvisado = "";
    private boolean tetoBloqueado;
    private long geracao;

    @Autowired
    public Freio(JarvisProperties props, MedidorDeCusto medidor, AvisosProativos avisos,
            FalasService falas, JsonMapper json) {
        this(props, medidor, avisos, falas, json, Clock.systemUTC(), props.pastaDoDiario().resolve("freio.json"));
    }

    public Freio(JarvisProperties props, MedidorDeCusto medidor, AvisosProativos avisos,
            FalasService falas, JsonMapper json, Clock relogio, Path arquivo) {
        this.props = props; this.medidor = medidor; this.avisos = avisos; this.falas = falas;
        this.json = json; this.relogio = relogio; this.arquivo = arquivo;
        desde = relogio.instant();
        carregar();
        medidor.aoAtualizar(this::avaliarCusto);
    }

    public void aoPausar(Runnable ouvinte) { ouvintes.add(ouvinte); }
    public void aoRetomar(Runnable ouvinte) { retomadas.add(ouvinte); }
    public long geracao() { synchronized (trava) { return geracao; } }
    public Retrato retrato() { synchronized (trava) { return new Retrato(estado, desde, canal); } }

    public void pausar(String porCanal) {
        RuntimeException falha = null;
        synchronized (trava) {
            estado = Estado.PAUSADO; desde = relogio.instant(); canal = porCanal;
            geracao++;
            try { salvar(); } catch (IOException e) { falha = new IllegalStateException("Parei, mas não consegui persistir a pausa.", e); }
        }
        // Mesmo uma pausa repetida invalida trabalho que estivesse terminando.
        notificar();
        if (falha != null) throw falha;
    }

    public void retomar(String porCanal) {
        boolean mudou;
        synchronized (trava) {
            Estado anterior = estado;
            mudou = anterior == Estado.PAUSADO;
            estado = Estado.ATIVO; desde = relogio.instant(); canal = porCanal;
            try { salvar(); }
            catch (IOException e) { estado = anterior; throw new IllegalStateException("Não consegui persistir a retomada.", e); }
        }
        if (mudou) notificar(retomadas);
    }

    /**
     * Se o teto autônomo de hoje já foi atingido, pela última avaliação (o tique de um minuto ou
     * uma chamada ao {@link #podeAgirSozinho}). Só leitura: quem descreve o momento, como o
     * {@code RetratoDoMomento}, não pode disparar o aviso de teto por ter olhado.
     */
    public boolean tetoAtingido() { synchronized (trava) { return tetoBloqueado; } }

    public boolean podeAgirSozinho(String origem) {
        avaliarCusto();
        synchronized (trava) { return estado == Estado.ATIVO && !tetoBloqueado; }
    }

    /** Lineariza a gravação curta com a pausa; nunca segurar isto durante uma chamada ao modelo. */
    public <T> T escrever(long inicio, String origem, Escrita<T> escrita) throws IOException {
        if (!podeAgirSozinho(origem)) return null;
        synchronized (trava) {
            if (inicio != geracao || estado != Estado.ATIVO || tetoBloqueado) return null;
            return escrita.executar();
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void avaliarCusto() {
        double teto = props.tetoAutonomoDeCusto();
        boolean bloqueia = teto > 0 && medidor.gastoAutonomoDeHoje() >= teto;
        String hoje = LocalDate.ofInstant(relogio.instant(), props.zona()).toString();
        boolean notificar = false, avisar = false, liberou = false;
        synchronized (trava) {
            if (bloqueia && !tetoBloqueado) { geracao++; notificar = true; }
            liberou = tetoBloqueado && !bloqueia;
            tetoBloqueado = bloqueia;
            if (bloqueia && !hoje.equals(diaAvisado)) {
                String anterior = diaAvisado;
                diaAvisado = hoje;
                try { salvar(); avisar = true; }
                catch (IOException e) { diaAvisado = anterior; log.warn("Não consegui persistir o aviso de teto autônomo"); }
            }
        }
        if (notificar) notificar();
        if (liberou) notificar(retomadas);
        if (avisar) avisos.avisar("freio:custo", hoje, falas.frase("teto_autonomo", java.util.Map.of()), null);
    }

    private void notificar() {
        notificar(ouvintes);
    }
    private void notificar(List<Runnable> lista) {
        for (Runnable ouvinte : lista) {
            try { ouvinte.run(); }
            catch (RuntimeException e) { log.warn("Ouvinte do freio falhou: {}", e.getClass().getSimpleName()); }
        }
    }

    private void salvar() throws IOException {
        var no = json.createObjectNode().put("estado", estado.name()).put("desde", desde.toString())
                .put("canal", canal).put("diaAvisado", diaAvisado);
        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(no));
    }

    private void carregar() {
        if (!Files.exists(arquivo) && !Files.exists(ArquivoAtomico.backupDe(arquivo))) return;
        for (Path candidato : List.of(arquivo, ArquivoAtomico.backupDe(arquivo))) {
            try {
                var no = json.readTree(ArquivoAtomico.ler(candidato, 8192));
                Estado lido = Estado.valueOf(Json.texto(no.path("estado")));
                Instant instante = Instant.parse(Json.texto(no.path("desde")));
                estado = lido; desde = instante; canal = Json.texto(no.path("canal"));
                diaAvisado = Json.texto(no.path("diaAvisado")); return;
            } catch (IOException | RuntimeException e) { log.warn("Estado do freio ilegível; tentando recuperação"); }
        }
        estado = Estado.PAUSADO; canal = "recuperacao";
    }
}
