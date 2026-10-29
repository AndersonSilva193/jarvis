package dev.jarvis.web;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.holograma.DetalheHolograma;
import dev.jarvis.holograma.ExemplosDeHolograma;
import dev.jarvis.holograma.GaleriaDeHologramas;
import dev.jarvis.holograma.HologramaLineParser;
import dev.jarvis.holograma.HologramaService;
import dev.jarvis.holograma.MontagemDeHolograma;
import dev.jarvis.holograma.PromptDeHolograma;
import dev.jarvis.holograma.SaidaHolograma;
import jakarta.annotation.PreDestroy;

/**
 * Transmite um objeto de exemplo pelo SSE, peça por peça, como o modelo vai transmitir depois:
 * eventos {@code meta}, {@code peca}, {@code aviso} e {@code fim}, com
 * {@value #PAUSA_ENTRE_PECAS_MS} ms entre uma peça e a seguinte.
 *
 * <p>É um stream próprio, de um objeto só, e não o {@code /api/eventos}: aqui quem pediu é quem
 * recebe, e a conexão fecha no {@code fim}. O exemplo passa pela mesma {@link MontagemDeHolograma}
 * que a resposta do modelo vai passar, então o que a HUD aprende com ele vale para a geração real.
 *
 * <p>O token da HUD é exigido pelo {@link TokenHudFiltro}, em {@code Authorization: Bearer} —
 * esta rota <b>não</b> está na exceção do {@code ?token=}. A Fase 3 lê o stream com {@code fetch},
 * que manda cabeçalho; ver {@code docs/HOLOGRAMA.md}.
 *
 * <p><b>Galeria.</b> O {@code /gerar} consulta a {@link GaleriaDeHologramas} antes de falar com o
 * modelo: achando o pedido, reproduz o objeto salvo pelos mesmos eventos, com
 * {@value #PAUSA_DA_GALERIA_MS} ms entre peças (a montagem continua visível) e {@code origem: cache}
 * no {@code fim}. {@code novo=true} pula a consulta, e o objeto novo substitui o antigo.
 */
@RestController
public class HologramaController {

    private static final Logger log = LoggerFactory.getLogger(HologramaController.class);

    static final long PAUSA_ENTRE_PECAS_MS = 40;
    static final long PAUSA_DA_GALERIA_MS = 15;
    /** Cem peças a 40 ms são quatro segundos; um minuto é folga para cliente lento. */
    private static final Duration PRAZO_DO_STREAM = Duration.ofMinutes(1);
    /** O que o {@code canvas.toDataURL("image/png")} escreve antes dos bytes. */
    private static final String PREFIXO_PNG = "data:image/png;base64,";
    /** O teto de bytes da miniatura em base64, que cresce 4/3. */
    private static final int MAX_MINIATURA_BASE64 =
            PREFIXO_PNG.length() + (GaleriaDeHologramas.MAX_BYTES_MINIATURA + 2) / 3 * 4;

    /**
     * Folga sobre o pior caso da geração (a chamada inicial e as continuações): quem fecha o stream
     * no prazo é o gerador, com erro e fim.
     */
    private static final Duration PRAZO_DA_GERACAO = HologramaService.PRAZO_TOTAL.plusSeconds(10);

    private final ExemplosDeHolograma exemplos;
    private final HologramaLineParser parser;
    private final HologramaService geracao;
    private final GaleriaDeHologramas galeria;
    private final Duration pausa;
    private final Duration pausaDaGaleria;
    /** Uma thread virtual por stream: a pausa entre peças não pode prender a thread da requisição. */
    private final ExecutorService transmissao = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("holograma-exemplo-", 0).factory());

    @Autowired
    public HologramaController(ExemplosDeHolograma exemplos, HologramaLineParser parser,
            HologramaService geracao, GaleriaDeHologramas galeria) {
        this(exemplos, parser, geracao, galeria, Duration.ofMillis(PAUSA_ENTRE_PECAS_MS),
                Duration.ofMillis(PAUSA_DA_GALERIA_MS));
    }

    /** Para teste, que não quer esperar quatro segundos por objeto. */
    HologramaController(ExemplosDeHolograma exemplos, HologramaLineParser parser,
            HologramaService geracao, GaleriaDeHologramas galeria, Duration pausa, Duration pausaDaGaleria) {
        this.exemplos = exemplos;
        this.parser = parser;
        this.geracao = geracao;
        this.galeria = galeria;
        this.pausa = pausa;
        this.pausaDaGaleria = pausaDaGaleria;
    }

    /**
     * Gera um objeto com o modelo, peça por peça, pelos mesmos eventos do exemplo — mais
     * {@code erro} (sempre antes do {@code fim}) quando a geração para antes da hora.
     *
     * <p>{@code X-Accel-Buffering: no} pede ao nginx que não acumule: hoje o {@code location /api/}
     * já tem {@code proxy_buffering off}, e o cabeçalho segura a rota se um dia ela mudar de bloco.
     *
     * @param objeto  o que desenhar, até {@value PromptDeHolograma#MAX_OBJETO} caracteres
     * @param detalhe {@code rapido}, {@code normal} (o padrão) ou {@code detalhado}
     * @param provedor {@code haiku} ou {@code local}; ausente vale o {@code HOLOGRAMA_PROVEDOR}
     * @param novo    {@code true} ignora a galeria e gera de novo; o objeto novo substitui o salvo
     */
    @GetMapping(path = "/api/holograma/gerar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> gerar(@RequestParam(required = false) String objeto,
            @RequestParam(required = false) String detalhe,
            @RequestParam(required = false) String provedor,
            @RequestParam(required = false, defaultValue = "false") boolean novo) {
        String limpo = PromptDeHolograma.higienizar(objeto);
        Optional<DetalheHolograma> nivel = DetalheHolograma.porChave(detalhe);
        Optional<String> gerador = HologramaService.provedorDoPedido(provedor);
        if (limpo.isEmpty() || objeto.strip().length() > PromptDeHolograma.MAX_OBJETO || nivel.isEmpty()
                || gerador.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        if (!novo) {
            Optional<GaleriaDeHologramas.HologramaSalvo> salvo = galeria.buscar(limpo, nivel.get());
            if (salvo.isPresent()) {
                return reproduzir(salvo.get());
            }
        }
        SseEmitter emissor = new SseEmitter(PRAZO_DA_GERACAO.toMillis());
        geracao.gerar(limpo, nivel.get(), gerador.get(), emissor);
        return semBuffer(emissor);
    }

    /**
     * Retoma a última geração: a HUD manda o id que veio na {@code meta} e quantas peças tem, e
     * recebe pelo mesmo formato de eventos a meta, as peças que perdeu e — se o objeto não estava
     * completo — o que o modelo ainda escrever. É o "Continuar" da tela, e o que salva o celular
     * que perdeu a conexão com a aba em segundo plano.
     *
     * @return 400 com id torto ou contagem negativa; 404 quando não há mais o que retomar com esse
     *         id (outra geração tomou o lugar, ou passou a retenção)
     */
    @GetMapping(path = "/api/holograma/continuar", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> continuar(@RequestParam(required = false) String id,
            @RequestParam(required = false, defaultValue = "0") int recebidas) {
        if (!HologramaService.idValido(id) || recebidas < 0) {
            return ResponseEntity.badRequest().build();
        }
        SseEmitter emissor = new SseEmitter(PRAZO_DA_GERACAO.toMillis());
        if (!geracao.continuar(id, recebidas, emissor)) {
            return ResponseEntity.notFound().build();
        }
        return semBuffer(emissor);
    }

    /** Os objetos da galeria: os exemplos fixos primeiro, depois os gerados, do mais novo. */
    @GetMapping("/api/holograma/galeria")
    public List<GaleriaDeHologramas.Item> galeria() {
        return galeria.listar();
    }

    /** Reabre um objeto da galeria pelo SSE, como um pedido que achou o cache. */
    @GetMapping(path = "/api/holograma/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> abrir(@PathVariable String id) {
        if (!GaleriaDeHologramas.idValido(id)) {
            return ResponseEntity.badRequest().build();
        }
        return galeria.abrir(id).map(this::reproduzir).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** @return 204; 404 quando não existe; 409 nos exemplos, que são fixos */
    @DeleteMapping("/api/holograma/{id}")
    public ResponseEntity<Void> apagar(@PathVariable String id) {
        return switch (galeria.apagar(id)) {
            case APAGADO -> ResponseEntity.noContent().build();
            case FIXO -> ResponseEntity.status(409).build();
            case NAO_EXISTE -> ResponseEntity.notFound().build();
        };
    }

    /** O corpo do {@code POST} da miniatura: o {@code canvas.toDataURL("image/png")} da HUD. */
    public record Miniatura(String imagem) { }

    /**
     * Guarda a miniatura que a HUD capturou ao terminar a montagem. Só PNG, conferido pelos bytes —
     * o {@code data:} declarado não decide nada —, e até
     * {@value GaleriaDeHologramas#MAX_BYTES_MINIATURA} bytes.
     *
     * @return 204; 400 para imagem torta ou grande demais; 404 para objeto que a galeria não tem
     */
    @PostMapping("/api/holograma/{id}/miniatura")
    public ResponseEntity<Void> guardarMiniatura(@PathVariable String id,
            @RequestBody(required = false) Miniatura corpo) {
        if (!GaleriaDeHologramas.idValido(id)) {
            return ResponseEntity.badRequest().build();
        }
        if (!galeria.contem(id)) {
            return ResponseEntity.notFound().build();
        }
        String imagem = corpo == null ? null : corpo.imagem();
        if (imagem == null || !imagem.startsWith(PREFIXO_PNG) || imagem.length() > MAX_MINIATURA_BASE64) {
            return ResponseEntity.badRequest().build();
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(imagem.substring(PREFIXO_PNG.length()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        if (!"image/png".equals(PcDto.mimeDe(bytes))) {
            return ResponseEntity.badRequest().build();
        }
        return galeria.guardarMiniatura(id, bytes) ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /** A miniatura em PNG. A HUD lê com {@code fetch} e Bearer, como o resto desta rota. */
    @GetMapping("/api/holograma/{id}/miniatura")
    public ResponseEntity<byte[]> miniatura(@PathVariable String id) {
        return galeria.miniatura(id)
                .map(bytes -> ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                        .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300").body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<SseEmitter> semBuffer(SseEmitter emissor) {
        return ResponseEntity.ok()
                .header("X-Accel-Buffering", "no")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache")
                .body(emissor);
    }

    @GetMapping(path = "/api/holograma/exemplo/{nome}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> exemplo(@PathVariable String nome) {
        List<String> linhas = exemplos.linhas(nome).orElse(null);
        if (linhas == null) {
            return ResponseEntity.notFound().build();
        }
        SseEmitter emissor = new SseEmitter(PRAZO_DO_STREAM.toMillis());
        AtomicBoolean aberto = abertura(emissor);
        transmissao.submit(() -> transmitir(nome, linhas, emissor, aberto));
        return semBuffer(emissor);
    }

    @PreDestroy
    void fechar() {
        transmissao.shutdownNow();
    }

    private static AtomicBoolean abertura(SseEmitter emissor) {
        AtomicBoolean aberto = new AtomicBoolean(true);
        emissor.onCompletion(() -> aberto.set(false));
        emissor.onTimeout(() -> aberto.set(false));
        emissor.onError(erro -> aberto.set(false));
        return aberto;
    }

    private void transmitir(String nome, List<String> linhas, SseEmitter emissor, AtomicBoolean aberto) {
        MontagemDeHolograma montagem = new MontagemDeHolograma(parser);
        try {
            for (String linha : linhas) {
                if (!enviar(montagem.processar(linha), emissor, aberto, pausa)) {
                    return;
                }
            }
            if (enviar(montagem.terminar(), emissor, aberto, pausa)) {
                emissor.complete();
            }
        } catch (IOException | IllegalStateException e) {
            // O cliente foi embora no meio: não há a quem avisar.
            log.debug("Stream do holograma '{}' caiu: {}", nome, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            emissor.complete();
        }
    }

    /**
     * O objeto salvo pelos eventos de sempre: a meta com o id, as peças já normalizadas e o
     * {@code fim} com {@code origem: cache} e se a miniatura existe.
     */
    private ResponseEntity<SseEmitter> reproduzir(GaleriaDeHologramas.HologramaSalvo salvo) {
        List<SaidaHolograma> saidas = new ArrayList<>();
        saidas.add(new SaidaHolograma.Meta(salvo.meta(), salvo.id()));
        salvo.pecas().forEach(peca -> saidas.add(new SaidaHolograma.Peca(peca)));
        saidas.add(new SaidaHolograma.Fim(salvo.pecas().size(), 0)
                .comResumo(new SaidaHolograma.Resumo(salvo.provedor(),
                        salvo.modelo() == null ? "" : salvo.modelo(), null, null, null))
                .comId(salvo.id())
                .naGaleria(SaidaHolograma.ORIGEM_CACHE, galeria.temMiniatura(salvo.id())));
        SseEmitter emissor = new SseEmitter(PRAZO_DO_STREAM.toMillis());
        AtomicBoolean aberto = abertura(emissor);
        transmissao.submit(() -> {
            try {
                if (enviar(saidas, emissor, aberto, pausaDaGaleria)) {
                    emissor.complete();
                }
            } catch (IOException | IllegalStateException e) {
                log.debug("Reprodução do holograma {} caiu: {}", salvo.id(), e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                emissor.complete();
            }
        });
        return semBuffer(emissor);
    }

    /** Envia em ordem, pausando depois de cada peça. Falso quando a conexão já fechou. */
    private static boolean enviar(List<SaidaHolograma> saidas, SseEmitter emissor, AtomicBoolean aberto,
            Duration pausa) throws IOException, InterruptedException {
        for (SaidaHolograma saida : saidas) {
            if (!aberto.get()) {
                return false;
            }
            emissor.send(SseEmitter.event().name(saida.evento()).data(saida.dados(), MediaType.APPLICATION_JSON));
            if (saida instanceof SaidaHolograma.Peca && !pausa.isZero()) {
                Thread.sleep(pausa);
            }
        }
        return aberto.get();
    }
}
