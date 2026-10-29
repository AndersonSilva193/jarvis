package dev.jarvis.holograma;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.ia.provedor.BlocoDeSistema;
import dev.jarvis.ia.provedor.Cancelamento;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.FalhaLocal;
import dev.jarvis.ia.provedor.InstrucoesDeSistema;
import dev.jarvis.ia.provedor.Mensagem;
import dev.jarvis.ia.provedor.OpcoesDoPedido;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorAnthropic;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.ProvedorLocal;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import jakarta.annotation.PreDestroy;

/**
 * Gera um holograma com o modelo e manda cada peça pelo SSE <b>enquanto o modelo ainda escreve</b>.
 *
 * <p>O caminho é um só: delta do modelo → {@link BufferDeLinhas} → linha completa →
 * {@link MontagemDeHolograma} (o mesmo parser e o mesmo validador dos exemplos) →
 * {@code emitter.send}. Nada espera a resposta inteira: a primeira peça sai quando a primeira linha
 * de peça fecha.
 *
 * <p><b>Quem gera.</b> {@code HOLOGRAMA_PROVEDOR=local} tenta o Ollama do PC, se a chave mestra
 * ({@code IA_LOCAL_ATIVA}) estiver ligada e a {@link DisponibilidadeLocal} disser que pode. O Claude
 * assume quando o local não pode, e quando ele falha <b>antes de qualquer peça</b> — depois da
 * primeira não há reserva: refazer com outro modelo desenharia um objeto diferente por cima do que
 * já está na tela. Aí sai {@code erro}, e as peças ficam.
 *
 * <p><b>Quem para.</b> Quatro coisas cortam a geração, e todas pelo mesmo {@link Cancelamento}, que
 * fecha o stream da Anthropic ou cancela o {@code inferir} no PC: o limite de
 * {@value HologramaValidator#MAX_PECAS} peças, o prazo de {@link #PRAZO}, o cliente que foi embora
 * (percebido pelo ping, já que antes da primeira peça não há escrita para falhar) e uma geração nova,
 * que substitui esta — há uma cena só na HUD e uma GPU só no PC.
 */
@Service
public class HologramaService {

    private static final Logger log = LoggerFactory.getLogger(HologramaService.class);

    /** Quanto a geração inteira pode levar, do pedido ao último evento. */
    public static final Duration PRAZO = Duration.ofSeconds(180);
    /** Um comentário SSE a cada tanto: é a escrita que revela o cliente que saiu. */
    static final Duration PING = Duration.ofSeconds(3);

    static final String LOCAL = "local";
    static final String ANTHROPIC = "anthropic";

    static final String TEXTO_TEMPO = "O modelo não respondeu a tempo.";
    static final String TEXTO_FALHA = "Falha ao gerar o holograma.";
    static final String TEXTO_SUBSTITUIDO = "A geração foi substituída por outra.";

    private final ProvedorDeModelo anthropic;
    private final ProvedorDeModelo local;
    private final Supplier<DisponibilidadeLocal.Situacao> disponibilidade;
    private final JarvisProperties props;
    private final PromptDeHolograma prompt;
    private final HologramaLineParser parser;
    private final Duration prazo;
    private final Duration ping;
    /** O provedor configurado, resolvido uma vez: o WARN de valor torto sai na subida. */
    private final String provedorConfigurado;

    private final AtomicReference<Geracao> atual = new AtomicReference<>();
    private final ExecutorService geracoes = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("holograma-", 0).factory());
    private final ScheduledExecutorService relogio = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("holograma-relogio").daemon().factory());

    @Autowired
    public HologramaService(ProvedorAnthropic anthropic, ProvedorLocal local,
            DisponibilidadeLocal disponibilidade, JarvisProperties props, PromptDeHolograma prompt,
            HologramaLineParser parser) {
        this(anthropic, local, disponibilidade::agora, props, prompt, parser, PRAZO, PING);
    }

    /** Para teste: provedores de mentira e relógios curtos. Público porque o teste HTTP mora em {@code web}. */
    public HologramaService(ProvedorDeModelo anthropic, ProvedorDeModelo local,
            Supplier<DisponibilidadeLocal.Situacao> disponibilidade, JarvisProperties props,
            PromptDeHolograma prompt, HologramaLineParser parser, Duration prazo, Duration ping) {
        this.anthropic = anthropic;
        this.local = local;
        this.disponibilidade = disponibilidade;
        this.props = props;
        this.prompt = prompt;
        this.parser = parser;
        this.prazo = prazo;
        this.ping = ping;
        this.provedorConfigurado = resolverProvedor(props.provedorDoHolograma());
    }

    private static String resolverProvedor(String bruto) {
        String chave = bruto.toLowerCase(Locale.ROOT);
        if (chave.isEmpty() || chave.equals(LOCAL)) return LOCAL;
        if (chave.equals(ANTHROPIC)) return ANTHROPIC;
        log.warn("HOLOGRAMA_PROVEDOR: '{}' não é local nem anthropic; seguindo com local", bruto);
        return LOCAL;
    }

    /**
     * Começa a gerar e volta na hora: o trabalho roda numa thread virtual, e quem fecha o
     * {@code emitter} é ela. Uma geração em andamento é substituída.
     *
     * @param objeto já higienizado por {@link PromptDeHolograma#higienizar}
     */
    public void gerar(String objeto, DetalheHolograma detalhe, SseEmitter emitter) {
        Geracao geracao = new Geracao(objeto, detalhe, emitter);
        Geracao anterior = atual.getAndSet(geracao);
        if (anterior != null) {
            anterior.cancelamento.cancelar(SUBSTITUIDO);
        }
        emitter.onCompletion(() -> geracao.cancelamento.cancelar(CLIENTE));
        emitter.onTimeout(() -> geracao.cancelamento.cancelar(TEMPO));
        emitter.onError(erro -> geracao.cancelamento.cancelar(CLIENTE));
        geracoes.submit(geracao::rodar);
    }

    @PreDestroy
    public void fechar() {
        Geracao geracao = atual.getAndSet(null);
        if (geracao != null) {
            geracao.cancelamento.cancelar(CLIENTE);
        }
        relogio.shutdownNow();
        geracoes.shutdownNow();
    }

    // Os motivos do corte, que viram o "fim=" do log.
    private static final String LIMITE = "limite";
    private static final String TEMPO = "tempo";
    private static final String CLIENTE = "cliente";
    private static final String SUBSTITUIDO = "substituido";

    /** Uma geração: o estado de um pedido, do primeiro delta ao último evento. */
    private final class Geracao {
        private final String objeto;
        private final DetalheHolograma detalhe;
        private final SseEmitter emitter;
        private final Cancelamento cancelamento = new Cancelamento();
        private final MontagemDeHolograma montagem = new MontagemDeHolograma(parser);
        private final BufferDeLinhas buffer = new BufferDeLinhas();
        /** Segura a montagem: os deltas do local chegam na thread da requisição do agente. */
        private final Object montando = new Object();
        /** Segura o emitter: o ping escreve de outra thread. */
        private final ReentrantLock escrita = new ReentrantLock();
        private final long inicio = System.nanoTime();
        private volatile long primeiraPeca = -1;
        private volatile boolean clienteFora;
        private String provedorUsado = "";
        private String modeloUsado = "";

        Geracao(String objeto, DetalheHolograma detalhe, SseEmitter emitter) {
            this.objeto = objeto;
            this.detalhe = detalhe;
            this.emitter = emitter;
        }

        void rodar() {
            ScheduledFuture<?> batidas = relogio.scheduleAtFixedRate(this::pingar,
                    ping.toMillis(), ping.toMillis(), TimeUnit.MILLISECONDS);
            ScheduledFuture<?> limite = relogio.schedule(() -> cancelamento.cancelar(TEMPO),
                    prazo.toMillis(), TimeUnit.MILLISECONDS);
            String fim;
            try {
                fim = gerarComReserva();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fim = CLIENTE;
            } catch (RuntimeException e) {
                log.warn("Holograma falhou: {}", e.toString());
                fim = encerrarComErro(TEXTO_FALHA);
            } finally {
                batidas.cancel(false);
                limite.cancel(false);
                atual.compareAndSet(this, null);
            }
            if (!clienteFora) {
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    // Já completado pelo timeout ou pelo erro do próprio container.
                }
            }
            registrar(fim);
        }

        /** O provedor configurado, e o Claude quando o local não pode ou cai antes de qualquer peça. */
        private String gerarComReserva() throws InterruptedException {
            boolean usarLocal = false;
            String motivo = "";
            if (LOCAL.equals(provedorConfigurado)) {
                if (!props.iaLocalAtiva()) {
                    motivo = "ia_local_desligada";
                } else {
                    DisponibilidadeLocal.Situacao situacao = disponibilidade.get();
                    usarLocal = situacao.pode();
                    motivo = situacao.motivo();
                }
                if (!usarLocal) {
                    log.info("Holograma: local → anthropic: {}", motivo);
                }
            }
            if (usarLocal) {
                provedorUsado = LOCAL;
                modeloUsado = "";
                try {
                    return transmitir(local, opcoes(true));
                } catch (IOException e) {
                    if (cancelamento.cancelado()) {
                        return porCorte();
                    }
                    String porque = e instanceof FalhaLocal f ? f.motivo() : e.getClass().getSimpleName();
                    synchronized (montando) {
                        if (montagem.pecas() > 0) {
                            log.info("Holograma: o local falhou depois de {} peça(s) ({}); sem reserva",
                                    montagem.pecas(), porque);
                            return encerrarComErro(TEXTO_FALHA);
                        }
                        // O que o local deixou pela metade não é linha de ninguém.
                        buffer.resto();
                    }
                    log.info("Holograma: local → anthropic: {}", porque);
                }
            }
            provedorUsado = ANTHROPIC;
            modeloUsado = !props.modeloDoHolograma().isEmpty() ? props.modeloDoHolograma()
                    : props.anthropic() == null ? "" : props.anthropic().modelo();
            try {
                return transmitir(anthropic, opcoes(false));
            } catch (IOException e) {
                if (cancelamento.cancelado()) {
                    return porCorte();
                }
                log.warn("Holograma: a Anthropic falhou: {}", e.getMessage());
                return encerrarComErro(TEXTO_FALHA);
            }
        }

        private OpcoesDoPedido opcoes(boolean doLocal) {
            long restante = Math.max(1_000, prazo.toMillis() - decorrido().toMillis());
            OpcoesDoPedido opcoes = OpcoesDoPedido.curta(Origem.HOLOGRAMA, detalhe.maxTokens(),
                    Duration.ofMillis(restante));
            return doLocal ? opcoes.comTemperatura(props.temperaturaDoHolograma())
                    : opcoes.comModelo(props.modeloDoHolograma());
        }

        /** Uma tentativa num provedor. Volta o motivo do fim; corte de fora vira exceção ou corte. */
        private String transmitir(ProvedorDeModelo provedor, OpcoesDoPedido opcoes)
                throws IOException, InterruptedException {
            PedidoAoModelo pedido = new PedidoAoModelo(
                    InstrucoesDeSistema.de(new BlocoDeSistema(prompt.sistema(objeto, detalhe), null)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "Gere: " + objeto)), null, opcoes);
            RespostaDoModelo resposta = provedor.transmitir(pedido, this::aoTexto, cancelamento);
            if (cancelamento.cancelado()) {
                return porCorte();
            }
            synchronized (montando) {
                enviar(montagem.processar(buffer.resto()));
                if (resposta.motivo() == RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS) {
                    log.info("Holograma: o modelo bateu no teto de {} tokens", detalhe.maxTokens());
                }
                enviar(montagem.terminar());
            }
            return "modelo";
        }

        /** Cada delta: as linhas que fecharam vão para a tela na hora. */
        private void aoTexto(String delta) {
            synchronized (montando) {
                if (cancelamento.cancelado()) {
                    return;
                }
                for (String linha : buffer.alimentar(delta)) {
                    enviar(montagem.processar(linha));
                    if (montagem.cheia()) {
                        cancelamento.cancelar(LIMITE);
                        return;
                    }
                }
            }
        }

        /** O fecho de quem foi cortado de fora, pelo motivo do corte. */
        private String porCorte() {
            String motivo = cancelamento.motivo();
            synchronized (montando) {
                switch (motivo) {
                    case LIMITE -> enviar(montagem.encerrar());
                    case TEMPO -> enviar(montagem.interromper(TEXTO_TEMPO));
                    case SUBSTITUIDO -> enviar(montagem.interromper(TEXTO_SUBSTITUIDO));
                    default -> { } // O cliente saiu: não há a quem dizer.
                }
            }
            return motivo;
        }

        private String encerrarComErro(String texto) {
            synchronized (montando) {
                enviar(montagem.interromper(texto));
            }
            return "erro";
        }

        private void enviar(List<SaidaHolograma> saidas) {
            for (SaidaHolograma saida : saidas) {
                if (clienteFora) {
                    return;
                }
                escrita.lock();
                try {
                    emitter.send(SseEmitter.event().name(saida.evento())
                            .data(saida.dados(), MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    saiu();
                    return;
                } finally {
                    escrita.unlock();
                }
                if (saida instanceof SaidaHolograma.Peca && primeiraPeca < 0) {
                    primeiraPeca = System.nanoTime();
                }
            }
        }

        private void pingar() {
            if (clienteFora || cancelamento.cancelado()) {
                return;
            }
            escrita.lock();
            try {
                emitter.send(SseEmitter.event().comment("gerando"));
            } catch (IOException | IllegalStateException e) {
                saiu();
            } finally {
                escrita.unlock();
            }
        }

        private void saiu() {
            clienteFora = true;
            cancelamento.cancelar(CLIENTE);
        }

        private Duration decorrido() {
            return Duration.ofNanos(System.nanoTime() - inicio);
        }

        private void registrar(String fim) {
            String primeira = primeiraPeca < 0 ? "-"
                    : segundos(Duration.ofNanos(primeiraPeca - inicio));
            log.info("Holograma '{}' ({}): provedor={}{} 1ª peça em {}, {} peças, {} linhas descartadas, "
                    + "{} avisos, total {}, fim={}",
                    objeto.length() > 40 ? objeto.substring(0, 40) + "…" : objeto, detalhe.chave(),
                    provedorUsado.isEmpty() ? "-" : provedorUsado,
                    modeloUsado.isEmpty() ? "" : " modelo=" + modeloUsado,
                    primeira, montagem.pecas(), montagem.descartadas(), montagem.avisos(),
                    segundos(decorrido()), fim);
        }
    }

    private static String segundos(Duration duracao) {
        return String.format(Locale.ROOT, "%.1f s", duracao.toMillis() / 1000.0);
    }
}
