package dev.jarvis.voz;

import java.io.IOException;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;

/**
 * O caminho de uma fala: limpa o markdown, procura no cache, sintetiza se precisar.
 *
 * Nunca lança por causa da ElevenLabs. O pior resultado possível é
 * {@link Fala#paraNavegador(String)}, que manda a HUD falar com a voz do sistema — a tela nunca
 * fica muda por culpa de cota, chave ou rede.
 */
@Service
public class VozService {

    private static final Logger log = LoggerFactory.getLogger(VozService.class);
    /** Acima disto a fala não é uma fala, é um texto — e sairia caro. A HUD lê pelo navegador. */
    public static final int LIMITE_DE_CARACTERES = 2_500;

    /**
     * O que a HUD deve fazer com esta fala.
     *
     * @param provedor "elevenlabs" ou "navegador"
     * @param id       chave do áudio no cache, quando o provedor é a ElevenLabs
     * @param motivo   por que a HUD vai falar sozinha, quando o provedor é o navegador
     */
    public record Fala(String provedor, String id, String motivo) {

        public static Fala paraNavegador(String motivo) {
            return new Fala("navegador", null, motivo);
        }

        public static Fala daElevenLabs(String id) {
            return new Fala("elevenlabs", id, null);
        }
    }

    private final ElevenLabsVoz elevenLabs;
    private final CacheDeVoz cache;
    private final MonitorDeCotaVoz monitor;
    private final DiarioDeVoz diario;
    private final JarvisProperties props;

    public VozService(ElevenLabsVoz elevenLabs, CacheDeVoz cache, MonitorDeCotaVoz monitor,
            DiarioDeVoz diario, JarvisProperties props) {
        this.elevenLabs = elevenLabs;
        this.cache = cache;
        this.monitor = monitor;
        this.diario = diario;
        this.props = props;
    }

    /** O MP3 guardado com este id, se existir. */
    public Optional<byte[]> audio(String id) {
        return cache.buscar(id);
    }

    /**
     * Prepara a fala deste texto.
     *
     * A ordem importa: o cache vem antes da checagem de cota, porque um áudio já gravado não
     * custa caractere nenhum — a frase repetida continua saindo na voz boa mesmo com a cota
     * na reserva.
     */
    public Fala preparar(String texto) {
        String limpo = TextoParaVoz.limpar(texto);
        if (limpo.isEmpty()) {
            return Fala.paraNavegador("texto vazio");
        }
        if (!props.vozRemotaConfigurada()) {
            return Fala.paraNavegador("desligado");
        }
        String id = cache.chave(limpo);
        if (cache.existe(id)) {
            diario.acertouCache();
            return Fala.daElevenLabs(id);
        }
        if (limpo.length() > LIMITE_DE_CARACTERES) {
            return Fala.paraNavegador("texto longo");
        }
        if (!monitor.podeSintetizar(limpo.length())) {
            return Fala.paraNavegador(motivoAtual());
        }
        return sintetizar(id, limpo);
    }

    private Fala sintetizar(String id, String texto) {
        try {
            byte[] audio = elevenLabs.sintetizar(texto);
            cache.guardar(id, audio);
            monitor.registrarUso(texto.length());
            return Fala.daElevenLabs(id);
        } catch (CotaDeVozException e) {
            monitor.registrarRecusa(e);
            return Fala.paraNavegador(motivoAtual());
        } catch (IOException | RuntimeException e) {
            // Fora do ar: só esta fala vai para o navegador. O modo não muda, então a próxima
            // tenta de novo — uma instabilidade de rede não derruba a voz pelo resto do dia.
            log.warn("Não consegui sintetizar a fala: {}", e.getMessage());
            return Fala.paraNavegador("indisponivel");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Fala.paraNavegador("indisponivel");
        }
    }

    private String motivoAtual() {
        MonitorDeCotaVoz.Motivo motivo = monitor.motivo();
        return motivo == null ? "indisponivel" : motivo.name().toLowerCase();
    }

    /** O estado da voz como a HUD e a ferramenta status_voz mostram. */
    public Status status() {
        SaldoDeVoz saldo = monitor.saldo();
        MonitorDeCotaVoz.Motivo motivo = monitor.motivo();
        return new Status(
                monitor.remotaAtiva() ? "elevenlabs" : "navegador",
                saldo.usados(),
                saldo.limite(),
                saldo.percentualUsado(),
                saldo.renovaEm() == null ? null : saldo.renovaEm().atZone(props.zona()).toString(),
                motivo == null ? null : motivo.name().toLowerCase(),
                motivo == null ? null : motivo.explicacao());
    }

    /** Data em String ISO-8601: o JsonMapper do projeto é cru e não serializa ZonedDateTime. */
    public record Status(String provedor, long caracteresUsados, long limite, int percentual,
            String renovaEm, String motivo, String explicacao) {
    }
}
