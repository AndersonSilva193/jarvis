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
    static final String TEXTO_SEM_PECAS = "O modelo não devolveu um objeto válido.";
    static final String SEM_PECAS = "sem_pecas";
    private static final int TRECHO_DO_LOG = 300;
    static final String TEXTO_SUBSTITUIDO = "A geração foi substituída por outra.";

    /** Quanto o Haiku pode ficar mudo antes de a geração passar ao local (só se o local puder). */
    public static final Duration PRIMEIRA_RESPOSTA = Duration.ofSeconds(45);

    static final String SEM_RESPOSTA = "sem_resposta";

    static final String AVISO_HAIKU_MUDO = "O Haiku não respondeu em 45 s; troquei para o gerador local.";
    static final String AVISO_HAIKU_FALHOU = "O Haiku falhou; troquei para o gerador local.";
    static final String AVISO_LOCAL_FALHOU = "O gerador local falhou; troquei para o Haiku.";
    static final String AVISO_LOCAL_FORA = "O gerador local não está disponível; usei o Haiku.";

    private final ProvedorDeModelo anthropic;
    private final ProvedorDeModelo local;
    private final Supplier<DisponibilidadeLocal.Situacao> disponibilidade;
    private final Supplier<String> modeloLocal;
    private final JarvisProperties props;
    private final PromptDeHolograma prompt;
    private final HologramaLineParser parser;
    private final Duration prazo;
    private final Duration ping;
    private final Duration primeiraResposta;
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
        this(anthropic, local, disponibilidade::agora, () -> disponibilidade.modeloAnunciado().orElse(""),
                props, prompt, parser, PRAZO, PING, PRIMEIRA_RESPOSTA);
    }

    /** Para teste: provedores de mentira e relógios curtos. Público porque o teste HTTP mora em {@code web}. */
    public HologramaService(ProvedorDeModelo anthropic, ProvedorDeModelo local,
            Supplier<DisponibilidadeLocal.Situacao> disponibilidade, JarvisProperties props,
            PromptDeHolograma prompt, HologramaLineParser parser, Duration prazo, Duration ping) {
        this(anthropic, local, disponibilidade, () -> "", props, prompt, parser, prazo, ping,
                PRIMEIRA_RESPOSTA);
    }

    public HologramaService(ProvedorDeModelo anthropic, ProvedorDeModelo local,
            Supplier<DisponibilidadeLocal.Situacao> disponibilidade, Supplier<String> modeloLocal,
            JarvisProperties props, PromptDeHolograma prompt, HologramaLineParser parser,
            Duration prazo, Duration ping, Duration primeiraResposta) {
        this.anthropic = anthropic;
        this.local = local;
        this.disponibilidade = disponibilidade;
        this.modeloLocal = modeloLocal;
        this.props = props;
        this.prompt = prompt;
        this.parser = parser;
        this.prazo = prazo;
        this.ping = ping;
        this.primeiraResposta = primeiraResposta;
        this.provedorConfigurado = resolverProvedor(props.provedorDoHolograma());
    }

    /** O provedor configurado (HOLOGRAMA_PROVEDOR): local ou anthropic, e anthropic no valor torto. */
    private static String resolverProvedor(String bruto) {
        String chave = bruto.toLowerCase(Locale.ROOT);
        if (chave.isEmpty() || chave.equals(ANTHROPIC)) return ANTHROPIC;
        if (chave.equals(LOCAL)) return LOCAL;
        log.warn("HOLOGRAMA_PROVEDOR: '{}' não é local nem anthropic; seguindo com anthropic", bruto);
        return ANTHROPIC;
    }

    /**
     * O gerador pedido na URL: {@code haiku} (ou {@code anthropic}) e {@code local}. Ausente ou em
     * branco é vazio dentro do {@code Optional} — vale o configurado —; valor desconhecido é
     * {@code Optional.empty()}, que o controlador transforma em 400.
     */
    public static java.util.Optional<String> provedorDoPedido(String bruto) {
        if (bruto == null || bruto.isBlank()) return java.util.Optional.of("");
        return switch (bruto.trim().toLowerCase(Locale.ROOT)) {
            case "haiku", ANTHROPIC -> java.util.Optional.of(ANTHROPIC);
            case LOCAL -> java.util.Optional.of(LOCAL);
            default -> java.util.Optional.empty();
        };
    }

    /**
     * Começa a gerar e volta na hora: o trabalho roda numa thread virtual, e quem fecha o
     * {@code emitter} é ela. Uma geração em andamento é substituída.
     *
     * @param objeto já higienizado por {@link PromptDeHolograma#higienizar}
     */
    public void gerar(String objeto, DetalheHolograma detalhe, SseEmitter emitter) {
        gerar(objeto, detalhe, "", emitter);
    }

    /**
     * @param provedor o de {@link #provedorDoPedido}: {@code local}, {@code anthropic} ou vazio para
     *                 o configurado
     */
    public void gerar(String objeto, DetalheHolograma detalhe, String provedor, SseEmitter emitter) {
        Geracao geracao = new Geracao(objeto, detalhe,
                provedor == null || provedor.isEmpty() ? provedorConfigurado : provedor, emitter);
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
        /** {@code local} ou {@code anthropic}: o do pedido, ou o configurado. */
        private final String provedorEscolhido;
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
        private volatile String provedorUsado = "";
        private volatile String modeloUsado = "";
        /** Os tokens que a API informou; {@code null} enquanto não informou (stream cortado, local). */
        private volatile Long tokensEntrada;
        private volatile Long tokensSaida;
        /** Chegou algum texto nesta tentativa? É o que o prazo de primeira resposta confere. */
        private volatile boolean textoRecebido;
        private volatile String ultimaFalha = "";
        /** Os primeiros caracteres da resposta desta tentativa, só para o log de resposta vazia. */
        private final StringBuilder inicioDaResposta = new StringBuilder();

        /** O começo da resposta numa linha só, para o log. */
        private String trecho() {
            String texto = inicioDaResposta.toString().replaceAll("\\s+", " ").strip();
            return texto.isEmpty() ? "(vazia)" : texto;
        }

        /** O erro mostrado quando as duas tentativas acabaram: a causa mais útil que há. */
        private String textoDaFalha() {
            return SEM_PECAS.equals(ultimaFalha) ? TEXTO_SEM_PECAS : TEXTO_FALHA;
        }

        Geracao(String objeto, DetalheHolograma detalhe, String provedorEscolhido, SseEmitter emitter) {
            this.objeto = objeto;
            this.detalhe = detalhe;
            this.provedorEscolhido = provedorEscolhido;
            this.emitter = emitter;
        }

        /** Um aviso fixo, em Java, de que o gerador trocou. Não conta nos avisos da montagem. */
        private void avisar(String texto) {
            enviar(List.of(new SaidaHolograma.Aviso(texto, true)));
        }

        /** O gasto desta geração. Custo só para a Anthropic: o local não cobra da API. */
        private SaidaHolograma.Resumo resumo() {
            Long entrada = tokensEntrada;
            Long saida = tokensSaida;
            Double custo = null;
            if (ANTHROPIC.equals(provedorUsado) && entrada != null && saida != null) {
                custo = custoEstimado(entrada, saida);
            }
            return new SaidaHolograma.Resumo(provedorUsado, modeloUsado,
                    LOCAL.equals(provedorUsado) ? null : entrada, LOCAL.equals(provedorUsado) ? null : saida,
                    custo);
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

        /**
         * O gerador escolhido e o outro de reserva, que só assume enquanto <b>nenhuma peça</b> saiu.
         * Anthropic escolhida → o local é a reserva (se a chave mestra e a disponibilidade deixarem);
         * local escolhido → o Claude é a reserva. Cada troca sai como {@code aviso} para a HUD.
         */
        private String gerarComReserva() throws InterruptedException {
            DisponibilidadeLocal.Situacao situacao = situacaoDoLocal();
            if (LOCAL.equals(provedorEscolhido)) {
                if (situacao.pode()) {
                    String fim = tentarLocal();
                    if (fim != null) return fim;
                    avisar(AVISO_LOCAL_FALHOU);
                } else {
                    log.info("Holograma: local → anthropic: {}", situacao.motivo());
                    avisar(AVISO_LOCAL_FORA);
                }
                String fim = tentarAnthropic(false);
                return fim != null ? fim : encerrarComErro(textoDaFalha());
            }
            String fim = tentarAnthropic(situacao.pode());
            if (fim != null) return fim;
            if (!situacao.pode()) {
                log.warn("Holograma: a Anthropic falhou e o local não pode ({})", situacao.motivo());
                return encerrarComErro(textoDaFalha());
            }
            avisar(SEM_RESPOSTA.equals(ultimaFalha) ? AVISO_HAIKU_MUDO : AVISO_HAIKU_FALHOU);
            fim = tentarLocal();
            return fim != null ? fim : encerrarComErro(textoDaFalha());
        }

        private DisponibilidadeLocal.Situacao situacaoDoLocal() {
            if (!props.iaLocalAtiva()) {
                return new DisponibilidadeLocal.Situacao(false, "ia_local_desligada");
            }
            return disponibilidade.get();
        }

        /** O fim da geração, ou {@code null} se o local caiu antes de qualquer peça e há reserva. */
        private String tentarLocal() throws InterruptedException {
            provedorUsado = LOCAL;
            modeloUsado = modeloLocal.get();
            try {
                return transmitir(local, opcoes(true, false));
            } catch (IOException e) {
                return aposFalha(e, "local");
            }
        }

        /**
         * @param comReserva há o local para assumir; é o que liga o prazo de {@link #primeiraResposta}
         *                   (sem reserva, cortar um Haiku lento só trocaria demora por erro)
         * @return o fim da geração, ou {@code null} se caiu antes de qualquer peça
         */
        private String tentarAnthropic(boolean comReserva) throws InterruptedException {
            provedorUsado = ANTHROPIC;
            modeloUsado = !props.modeloDoHolograma().isEmpty() ? props.modeloDoHolograma()
                    : props.anthropic() == null ? "" : props.anthropic().modelo();
            try {
                return transmitir(anthropic, opcoes(false, comReserva), comReserva ? primeiraResposta : null);
            } catch (IOException e) {
                return aposFalha(e, "anthropic");
            }
        }

        /**
         * O que fazer com um provedor que caiu: corte de fora fecha pelo motivo; peça já na tela não
         * tem reserva (outro modelo desenharia outro objeto por cima) e fecha com erro; antes da
         * primeira peça volta {@code null} para quem chama tentar o outro.
         */
        private String aposFalha(IOException e, String quem) {
            if (cancelamento.cancelado()) {
                return porCorte();
            }
            if (e instanceof java.net.http.HttpTimeoutException) {
                // Os cabeçalhos nem chegaram no prazo curto: é o mesmo mutismo, só que antes do stream.
                ultimaFalha = SEM_RESPOSTA;
            }
            String porque = e instanceof FalhaLocal f ? f.motivo()
                    : SEM_RESPOSTA.equals(ultimaFalha) ? SEM_RESPOSTA : e.getClass().getSimpleName();
            synchronized (montando) {
                if (montagem.pecas() > 0) {
                    log.info("Holograma: {} falhou depois de {} peça(s) ({}); sem reserva",
                            quem, montagem.pecas(), porque);
                    return encerrarComErro(TEXTO_FALHA);
                }
                // O que o provedor deixou pela metade não é linha de ninguém.
                buffer.resto();
            }
            log.info("Holograma: {} falhou antes da primeira peça: {}", quem, porque);
            return null;
        }

        private OpcoesDoPedido opcoes(boolean doLocal, boolean comPrazoCurto) {
            long restante = Math.max(1_000, prazo.toMillis() - decorrido().toMillis());
            if (comPrazoCurto) {
                // Até os cabeçalhos chegarem: um Haiku que nem abre a resposta cai antes dos 45 s.
                restante = Math.min(restante, primeiraResposta.toMillis());
            }
            int teto = Math.min(detalhe.maxTokens(), props.maxTokensDoHolograma());
            OpcoesDoPedido opcoes = OpcoesDoPedido.curta(Origem.HOLOGRAMA, teto,
                    Duration.ofMillis(restante));
            return doLocal ? opcoes.comTemperatura(props.temperaturaDoHolograma())
                    : opcoes.comModelo(props.modeloDoHolograma());
        }

        private String transmitir(ProvedorDeModelo provedor, OpcoesDoPedido opcoes)
                throws IOException, InterruptedException {
            return transmitir(provedor, opcoes, null);
        }

        /**
         * Uma tentativa num provedor. Volta o motivo do fim; corte de fora vira exceção ou corte.
         *
         * @param silencio quanto o provedor pode ficar sem entregar texto; {@code null} não limita
         */
        private String transmitir(ProvedorDeModelo provedor, OpcoesDoPedido opcoes, Duration silencio)
                throws IOException, InterruptedException {
            PedidoAoModelo pedido = new PedidoAoModelo(
                    InstrucoesDeSistema.de(new BlocoDeSistema(prompt.sistema(objeto, detalhe), null)),
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "Gere: " + objeto)), null, opcoes);
            // Um corte só desta tentativa (o silêncio), que não pode contaminar a geração inteira: a
            // reserva ainda vai usar o cancelamento geral. O geral desce para a tentativa.
            Cancelamento tentativa = new Cancelamento();
            cancelamento.aoCancelar(() -> tentativa.cancelar(cancelamento.motivo()));
            textoRecebido = false;
            ultimaFalha = "";
            inicioDaResposta.setLength(0);
            ScheduledFuture<?> mudez = silencio == null ? null : relogio.schedule(() -> {
                if (!textoRecebido && !tentativa.cancelado()) {
                    ultimaFalha = SEM_RESPOSTA;
                    tentativa.cancelar(SEM_RESPOSTA);
                }
            }, silencio.toMillis(), TimeUnit.MILLISECONDS);
            RespostaDoModelo resposta;
            try {
                resposta = provedor.transmitir(pedido, this::aoTexto, tentativa);
            } finally {
                if (mudez != null) mudez.cancel(false);
            }
            if (cancelamento.cancelado()) {
                return porCorte();
            }
            if (tentativa.cancelado()) {
                throw new IOException(tentativa.motivo());
            }
            anotarUso(resposta.uso());
            synchronized (montando) {
                enviar(montagem.processar(buffer.resto()));
                if (montagem.pecas() == 0) {
                    // Resposta que terminou "bem" sem desenhar nada: prosa, recusa ou formato errado.
                    // Vira falha para a reserva poder assumir; o trecho só vai ao log.
                    ultimaFalha = SEM_PECAS;
                    log.warn("Holograma: o modelo não devolveu nenhuma peça ({} linhas descartadas, motivo={}); "
                            + "resposta: {}", montagem.descartadas(), resposta.motivo(), trecho());
                    throw new IOException(SEM_PECAS);
                }
                if (resposta.motivo() == RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS) {
                    log.info("Holograma: o modelo bateu no teto de {} tokens", opcoes.maxTokens());
                }
                enviar(montagem.terminar());
            }
            return "modelo";
        }

        private void anotarUso(RespostaDoModelo.Uso uso) {
            if (uso.entrada() <= 0 && uso.saida() <= 0) return;
            // Prompt inteiro: o que a API cobra como entrada mais o cache, se houver.
            tokensEntrada = uso.entrada() + uso.cacheEscrito() + uso.cacheLido();
            tokensSaida = uso.saida();
        }

        /** Cada delta: as linhas que fecharam vão para a tela na hora. */
        private void aoTexto(String delta) {
            textoRecebido = true;
            if (inicioDaResposta.length() < TRECHO_DO_LOG) {
                inicioDaResposta.append(delta, 0, Math.min(delta.length(), TRECHO_DO_LOG - inicioDaResposta.length()));
            }
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
            for (SaidaHolograma original : saidas) {
                if (clienteFora) {
                    return;
                }
                // O fim carrega o gasto: quem sabe o provedor e os tokens é esta geração, não a montagem.
                SaidaHolograma saida = original instanceof SaidaHolograma.Fim fim && fim.resumo() == null
                        ? fim.comResumo(resumo()) : original;
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
            SaidaHolograma.Resumo resumo = resumo();
            log.info("Holograma '{}' ({}): provedor={}{} 1ª peça em {}, {} peças, {} linhas descartadas, "
                    + "{} avisos {}, tokens={}, custo estimado={}, total {}, fim={}",
                    objeto.length() > 40 ? objeto.substring(0, 40) + "…" : objeto, detalhe.chave(),
                    provedorUsado.isEmpty() ? "-" : provedorUsado,
                    modeloUsado.isEmpty() ? "" : " modelo=" + modeloUsado,
                    primeira, montagem.pecas(), montagem.descartadas(), montagem.avisos(), montagem.textosDeAviso(),
                    resumo.tokensEntrada() == null ? "?" : resumo.tokensEntrada() + "/" + resumo.tokensSaida(),
                    resumo.custoUsd() == null ? "-" : String.format(Locale.ROOT, "US$ %.4f", resumo.custoUsd()),
                    segundos(decorrido()), fim);
        }
    }

    /** US$ pela tabela configurada (por milhão de tokens), em seis casas: a conta é de centésimos de centavo. */
    double custoEstimado(long entrada, long saida) {
        double custo = (entrada * props.precoEntradaDoHolograma() + saida * props.precoSaidaDoHolograma()) / 1_000_000.0;
        return Math.round(custo * 1_000_000.0) / 1_000_000.0;
    }

    private static String segundos(Duration duracao) {
        return String.format(Locale.ROOT, "%.1f s", duracao.toMillis() / 1000.0);
    }
}
