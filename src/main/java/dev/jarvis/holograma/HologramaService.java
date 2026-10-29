package dev.jarvis.holograma;

import java.io.IOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
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
 * <p><b>Continuação.</b> A resposta que acaba sem a linha de fim — teto de tokens, ou stream que
 * terminou antes — ganha até {@value #MAX_CONTINUACOES} chamadas novas ao <b>mesmo</b> provedor, com
 * as linhas já aceitas como mensagem do assistente e o pedido de seguir só com o que falta. As peças
 * novas saem no mesmo SSE. Esgotadas as continuações, o {@code fim} sai {@code incompleto} com o
 * motivo, e a HUD oferece "Continuar".
 *
 * <p><b>Retomada.</b> A última geração fica guardada por {@link #RETENCAO} (montagem, meta e peças
 * que saíram). {@link #continuar} reenvia à HUD as peças que ela não recebeu — o celular que pôs a
 * aba em segundo plano perde a conexão no meio — e, se o objeto não estava completo, continua com o
 * modelo. Uma geração só: há uma cena só na HUD.
 *
 * <p><b>Quem para.</b> Quatro coisas cortam a geração, e todas pelo mesmo {@link Cancelamento}, que
 * fecha o stream da Anthropic ou cancela o {@code inferir} no PC: o limite de
 * {@value HologramaValidator#MAX_PECAS} peças, o prazo de {@link #PRAZO} por chamada, o cliente que
 * foi embora (percebido pelo ping, já que antes da primeira peça não há escrita para falhar) e uma
 * geração nova, que substitui esta — há uma cena só na HUD e uma GPU só no PC.
 *
 * <p><b>Galeria.</b> O objeto que fecha completo, com ao menos
 * {@value GaleriaDeHologramas#MINIMO_DE_PECAS} peças, é guardado na {@link GaleriaDeHologramas}
 * <b>antes</b> de o {@code fim} sair — a HUD manda a miniatura logo que o recebe, e o id já tem de
 * existir. Quem consulta a galeria antes de gerar é o controlador.
 */
@Service
public class HologramaService {

    private static final Logger log = LoggerFactory.getLogger(HologramaService.class);

    /** Quanto cada chamada ao modelo pode levar, a inicial e cada continuação. */
    public static final Duration PRAZO = Duration.ofSeconds(180);
    /** Quantas chamadas de continuação um objeto cortado recebe, por pedido. */
    public static final int MAX_CONTINUACOES = 2;
    /** O pior caso de um pedido: a chamada inicial e todas as continuações no prazo. */
    public static final Duration PRAZO_TOTAL = PRAZO.multipliedBy(MAX_CONTINUACOES + 1L);
    /** Por quanto tempo a última geração pode ser retomada pela HUD. */
    static final Duration RETENCAO = Duration.ofMinutes(30);
    /** Um comentário SSE a cada tanto: é a escrita que revela o cliente que saiu. */
    static final Duration PING = Duration.ofSeconds(3);
    /** Quanto o {@code /continuar} espera a geração anterior soltar a montagem. */
    private static final Duration ESPERA_DA_ANTERIOR = Duration.ofSeconds(5);

    static final String LOCAL = "local";
    static final String ANTHROPIC = "anthropic";

    static final String TEXTO_TEMPO = "O modelo não respondeu a tempo.";
    static final String TEXTO_FALHA = "Falha ao gerar o holograma.";
    static final String TEXTO_SEM_PECAS = "O modelo não devolveu um objeto válido.";
    static final String SEM_PECAS = "sem_pecas";
    private static final int TRECHO_DO_LOG = 300;
    static final String TEXTO_SUBSTITUIDO = "A geração foi substituída por outra.";
    /** O motivo do {@code fim} incompleto quando a última chamada bateu no teto. */
    static final String MOTIVO_TETO = "o modelo bateu no teto de tokens";

    /** Quanto o Haiku pode ficar mudo antes de a geração passar ao local (só se o local puder). */
    public static final Duration PRIMEIRA_RESPOSTA = Duration.ofSeconds(45);

    static final String SEM_RESPOSTA = "sem_resposta";

    static final String AVISO_HAIKU_MUDO = "O Haiku não respondeu em 45 s; troquei para o gerador local.";
    static final String AVISO_HAIKU_FALHOU = "O Haiku falhou; troquei para o gerador local.";
    static final String AVISO_LOCAL_FALHOU = "O gerador local falhou; troquei para o Haiku.";
    static final String AVISO_LOCAL_FORA = "O gerador local não está disponível; usei o Haiku.";

    private static final SecureRandom SORTEIO = new SecureRandom();
    private static final String ALFABETO = "0123456789abcdefghijklmnopqrstuvwxyz";
    private static final int TAMANHO_ID = 8;

    private final ProvedorDeModelo anthropic;
    private final ProvedorDeModelo local;
    private final Supplier<DisponibilidadeLocal.Situacao> disponibilidade;
    private final Supplier<String> modeloLocal;
    private final JarvisProperties props;
    /** Nula nos testes que não falam de galeria: aí nada é guardado. */
    private final GaleriaDeHologramas galeria;
    private final PromptDeHolograma prompt;
    private final HologramaLineParser parser;
    private final Duration prazo;
    private final Duration ping;
    private final Duration primeiraResposta;
    /** O provedor configurado, resolvido uma vez: o WARN de valor torto sai na subida. */
    private final String provedorConfigurado;

    private final AtomicReference<Geracao> atual = new AtomicReference<>();
    /** A última geração que terminou com peça na tela, para o {@link #continuar}. */
    private final AtomicReference<Retomada> retomavel = new AtomicReference<>();
    private final ExecutorService geracoes = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("holograma-", 0).factory());
    private final ScheduledExecutorService relogio = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("holograma-relogio").daemon().factory());

    @Autowired
    public HologramaService(ProvedorAnthropic anthropic, ProvedorLocal local,
            DisponibilidadeLocal disponibilidade, JarvisProperties props, PromptDeHolograma prompt,
            HologramaLineParser parser, GaleriaDeHologramas galeria) {
        this(anthropic, local, disponibilidade::agora, () -> disponibilidade.modeloAnunciado().orElse(""),
                props, prompt, parser, PRAZO, PING, PRIMEIRA_RESPOSTA, galeria);
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
        this(anthropic, local, disponibilidade, modeloLocal, props, prompt, parser, prazo, ping,
                primeiraResposta, null);
    }

    public HologramaService(ProvedorDeModelo anthropic, ProvedorDeModelo local,
            Supplier<DisponibilidadeLocal.Situacao> disponibilidade, Supplier<String> modeloLocal,
            JarvisProperties props, PromptDeHolograma prompt, HologramaLineParser parser,
            Duration prazo, Duration ping, Duration primeiraResposta, GaleriaDeHologramas galeria) {
        this.galeria = galeria;
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

    /** O formato do id que sai na {@code meta}: é o que o controlador confere antes de procurar. */
    public static boolean idValido(String id) {
        return id != null && id.matches("h[0-9a-z]{" + TAMANHO_ID + "}");
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
        iniciar(new Geracao(novoId(), objeto, detalhe,
                provedor == null || provedor.isEmpty() ? provedorConfigurado : provedor, emitter));
    }

    /**
     * Retoma a última geração: reenvia a meta e as peças a partir da {@code recebidas}-ésima (as que
     * a HUD não viu) e, se o objeto não estava completo, continua com o modelo. Volta {@code false}
     * quando não há o que retomar com esse id — outra geração tomou o lugar, ou passou a
     * {@link #RETENCAO} —, e aí não mexe no {@code emitter}.
     *
     * <p>A HUD pode desistir de uma conexão antes de o servidor perceber (aba em segundo plano):
     * se a geração deste id ainda está rodando, ela é cortada como "cliente fora" e esperada.
     */
    public boolean continuar(String id, int recebidas, SseEmitter emitter) {
        Geracao corrente = atual.get();
        if (corrente != null && corrente.id.equals(id)) {
            corrente.saiu();
            try {
                corrente.encerrada.await(ESPERA_DA_ANTERIOR.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        Retomada retomada = retomavel.get();
        if (retomada == null || !retomada.id().equals(id)
                || System.nanoTime() - retomada.guardadaEm() > RETENCAO.toNanos()
                || !retomavel.compareAndSet(retomada, null)) {
            return false;
        }
        iniciar(new Geracao(retomada, recebidas, emitter));
        return true;
    }

    private void iniciar(Geracao geracao) {
        Geracao anterior = atual.getAndSet(geracao);
        if (anterior != null) {
            anterior.cancelamento.cancelar(SUBSTITUIDO);
        }
        geracao.emitter.onCompletion(() -> geracao.cancelamento.cancelar(CLIENTE));
        geracao.emitter.onTimeout(() -> geracao.cancelamento.cancelar(TEMPO));
        geracao.emitter.onError(erro -> geracao.cancelamento.cancelar(CLIENTE));
        geracoes.submit(geracao::rodar);
    }

    private static String novoId() {
        StringBuilder id = new StringBuilder(TAMANHO_ID + 1).append('h');
        for (int i = 0; i < TAMANHO_ID; i++) {
            id.append(ALFABETO.charAt(SORTEIO.nextInt(ALFABETO.length())));
        }
        return id.toString();
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

    /**
     * O que sobra de uma geração para a HUD retomar: a montagem (ids usados, linhas aceitas, o
     * {@code fim} que saiu) e o que já foi para a tela, na ordem em que foi.
     */
    private record Retomada(String id, String objeto, DetalheHolograma detalhe, String provedor,
            String modelo, MontagemDeHolograma montagem, SaidaHolograma.Meta meta,
            List<SaidaHolograma.Peca> pecas, long guardadaEm) {
    }

    /** Uma geração: o estado de um pedido, do primeiro delta ao último evento. */
    private final class Geracao {
        private final String id;
        private final String objeto;
        private final DetalheHolograma detalhe;
        /** {@code local} ou {@code anthropic}: o do pedido, ou o configurado. */
        private final String provedorEscolhido;
        private final SseEmitter emitter;
        private final Cancelamento cancelamento = new Cancelamento();
        private final MontagemDeHolograma montagem;
        private final BufferDeLinhas buffer = new BufferDeLinhas();
        /** Na retomada: quantas peças a HUD já tem. Nulo numa geração nova. */
        private final Integer recebidas;
        /** O que já foi para a tela, na ordem: é o que a retomada reenvia. */
        private SaidaHolograma.Meta metaVista;
        private final List<SaidaHolograma.Peca> pecasVistas;
        /** Segura a montagem: os deltas do local chegam na thread da requisição do agente. */
        private final Object montando = new Object();
        /** Segura o emitter: o ping escreve de outra thread. */
        private final ReentrantLock escrita = new ReentrantLock();
        /** Solta quando a geração guardou o que a retomada precisa: é o que o {@code /continuar} espera. */
        private final CountDownLatch encerrada = new CountDownLatch(1);
        private final long inicio = System.nanoTime();
        private volatile long inicioDaChamada = System.nanoTime();
        private volatile ScheduledFuture<?> limite;
        private volatile long primeiraPeca = -1;
        private volatile boolean clienteFora;
        private volatile boolean fimEnviado;
        /** O objeto está na galeria: o {@code fim} diz à HUD que pode mandar a miniatura. */
        private volatile boolean naGaleria;
        private volatile String provedorUsado = "";
        private volatile String modeloUsado = "";
        /** Os tokens que a API informou, somados entre as chamadas; {@code null} enquanto não informou. */
        private volatile Long tokensEntrada;
        private volatile Long tokensSaida;
        private int chamadas;
        private int continuacoes;
        /** Chegou algum texto nesta tentativa? É o que o prazo de primeira resposta confere. */
        private volatile boolean textoRecebido;
        private volatile String ultimaFalha = "";
        /** Os primeiros caracteres da resposta desta tentativa, só para o log de resposta vazia. */
        private final StringBuilder inicioDaResposta = new StringBuilder();

        Geracao(String id, String objeto, DetalheHolograma detalhe, String provedorEscolhido,
                SseEmitter emitter) {
            this.id = id;
            this.objeto = objeto;
            this.detalhe = detalhe;
            this.provedorEscolhido = provedorEscolhido;
            this.emitter = emitter;
            this.montagem = new MontagemDeHolograma(parser);
            this.recebidas = null;
            this.pecasVistas = new ArrayList<>();
        }

        /** A retomada: mesma montagem, mesmo provedor, e o que já foi para a tela. */
        Geracao(Retomada retomada, int recebidas, SseEmitter emitter) {
            this.id = retomada.id();
            this.objeto = retomada.objeto();
            this.detalhe = retomada.detalhe();
            this.provedorEscolhido = retomada.provedor();
            this.provedorUsado = retomada.provedor();
            this.modeloUsado = retomada.modelo();
            this.emitter = emitter;
            this.montagem = retomada.montagem();
            this.metaVista = retomada.meta();
            this.pecasVistas = new ArrayList<>(retomada.pecas());
            this.recebidas = Math.max(0, Math.min(recebidas, pecasVistas.size()));
        }

        /** O começo da resposta numa linha só, para o log. */
        private String trecho() {
            String texto = inicioDaResposta.toString().replaceAll("\\s+", " ").strip();
            return texto.isEmpty() ? "(vazia)" : texto;
        }

        /** O erro mostrado quando as duas tentativas acabaram: a causa mais útil que há. */
        private String textoDaFalha() {
            return SEM_PECAS.equals(ultimaFalha) ? TEXTO_SEM_PECAS : TEXTO_FALHA;
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
            armarPrazo();
            String fim = CLIENTE;
            try {
                fim = recebidas != null ? retomar() : gerarComReserva();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fim = CLIENTE;
            } catch (RuntimeException e) {
                log.warn("Holograma falhou: {}", e.toString());
                fim = encerrarComErro(TEXTO_FALHA);
            } finally {
                batidas.cancel(false);
                ScheduledFuture<?> prazoArmado = limite;
                if (prazoArmado != null) prazoArmado.cancel(false);
                atual.compareAndSet(this, null);
            }
            if (!clienteFora) {
                try {
                    emitter.complete();
                } catch (RuntimeException e) {
                    // Já completado pelo timeout ou pelo erro do próprio container.
                }
            }
            guardar(fim);
            encerrada.countDown();
            registrar(fim);
        }

        /**
         * Guarda o que a HUD pode querer retomar. Substituída não guarda: a geração nova é a que
         * importa, e guardar esta por cima dela apagaria o que se poderia retomar de lá.
         */
        private void guardar(String fim) {
            if (SUBSTITUIDO.equals(fim) || montagem.pecas() == 0) {
                return;
            }
            synchronized (montando) {
                retomavel.set(new Retomada(id, objeto, detalhe, provedorUsado, modeloUsado, montagem,
                        metaVista, List.copyOf(pecasVistas), System.nanoTime()));
            }
        }

        /** A retomada: reenvia o que a HUD perdeu e, se o objeto não fechou completo, continua. */
        private String retomar() throws InterruptedException {
            synchronized (montando) {
                List<SaidaHolograma> perdidas = new ArrayList<>();
                if (metaVista != null) perdidas.add(metaVista);
                perdidas.addAll(pecasVistas.subList(recebidas, pecasVistas.size()));
                enviar(perdidas);
                if (montagem.completo()) {
                    naGaleria = galeria != null && galeria.contem(id);
                    enviar(List.of(montagem.fim().orElseThrow()));
                    return "reenvio";
                }
                // Fechada como incompleta (teto, prazo, falha) ou ainda aberta (o cliente caiu):
                // reabre antes do laço, que só continua o que não terminou.
                montagem.continuar();
            }
            log.info("Holograma '{}' ({}): retomada com {} de {} peças na HUD", curto(objeto),
                    detalhe.chave(), recebidas, pecasVistas.size());
            return continuarAteOFim(MontagemDeHolograma.MOTIVO_SEM_FIM);
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
                return transmitir(local, opcoes(true, false), null);
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
            long restante = Math.max(1_000,
                    prazo.toMillis() - Duration.ofNanos(System.nanoTime() - inicioDaChamada).toMillis());
            if (comPrazoCurto) {
                // Até os cabeçalhos chegarem: um Haiku que nem abre a resposta cai antes dos 45 s.
                restante = Math.min(restante, primeiraResposta.toMillis());
            }
            OpcoesDoPedido opcoes = OpcoesDoPedido.curta(Origem.HOLOGRAMA,
                    props.maxTokensDoHolograma(detalhe.chave()), Duration.ofMillis(restante));
            return doLocal ? opcoes.comTemperatura(props.temperaturaDoHolograma())
                    : opcoes.comModelo(props.modeloDoHolograma());
        }

        /** O prazo de uma chamada, rearmado a cada continuação: cada uma tem o seu {@link #prazo}. */
        private void armarPrazo() {
            ScheduledFuture<?> anterior = limite;
            if (anterior != null) anterior.cancel(false);
            inicioDaChamada = System.nanoTime();
            limite = relogio.schedule(() -> cancelamento.cancelar(TEMPO), prazo.toMillis(),
                    TimeUnit.MILLISECONDS);
        }

        /**
         * A primeira chamada num provedor e, se ela acabou sem a linha de fim, as continuações.
         * Volta o motivo do fim; corte de fora vira corte, e falha antes de peça vira exceção.
         *
         * @param silencio quanto o provedor pode ficar sem entregar texto; {@code null} não limita
         */
        private String transmitir(ProvedorDeModelo provedor, OpcoesDoPedido opcoes, Duration silencio)
                throws IOException, InterruptedException {
            RespostaDoModelo resposta = chamar(provedor, opcoes, silencio,
                    List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "Gere: " + objeto)), "inicial");
            if (resposta == null) {
                return porCorte();
            }
            synchronized (montando) {
                if (montagem.pecas() == 0) {
                    // Resposta que terminou "bem" sem desenhar nada: prosa, recusa ou formato errado.
                    // Vira falha para a reserva poder assumir; o trecho só vai ao log.
                    ultimaFalha = SEM_PECAS;
                    log.warn("Holograma: o modelo não devolveu nenhuma peça ({} linhas descartadas, motivo={}); "
                            + "resposta: {}", montagem.descartadas(), resposta.motivo(), trecho());
                    throw new IOException(SEM_PECAS);
                }
            }
            return continuarAteOFim(motivoDoCorte(resposta));
        }

        /**
         * Enquanto o objeto não fechou e há continuação no orçamento, pede ao mesmo provedor que siga
         * de onde parou. Esgotado o orçamento, fecha o {@code fim} como incompleto.
         *
         * @param motivo o que dizer no {@code fim} se nenhuma continuação chegar à linha de fim
         */
        private String continuarAteOFim(String motivo) throws InterruptedException {
            while (!montagem.terminou() && continuacoes < MAX_CONTINUACOES) {
                continuacoes++;
                synchronized (montando) {
                    montagem.continuar();
                }
                armarPrazo();
                boolean doLocal = LOCAL.equals(provedorUsado);
                RespostaDoModelo resposta;
                try {
                    resposta = chamar(doLocal ? local : anthropic, opcoes(doLocal, false), null,
                            mensagensDaContinuacao(), "continuação " + continuacoes);
                } catch (IOException e) {
                    String fim = aposFalha(e, provedorUsado);
                    return fim != null ? fim : encerrarComErro(TEXTO_FALHA);
                }
                if (resposta == null) {
                    return porCorte();
                }
                motivo = motivoDoCorte(resposta);
            }
            synchronized (montando) {
                if (!montagem.terminou()) {
                    enviar(montagem.incompleto(motivo));
                    return "incompleto";
                }
            }
            return "modelo";
        }

        /** O pedido original, o que já chegou como fala do modelo, e o pedido de seguir. */
        private List<Mensagem> mensagensDaContinuacao() {
            String recebido;
            synchronized (montando) {
                recebido = String.join("\n", montagem.linhasAceitas());
            }
            return List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "Gere: " + objeto),
                    Mensagem.texto(Mensagem.Papel.ASSISTENTE, recebido),
                    Mensagem.texto(Mensagem.Papel.USUARIO, PromptDeHolograma.CONTINUAR));
        }

        /**
         * Uma ida ao modelo, com o texto indo para a tela enquanto chega. Volta a resposta, ou
         * {@code null} quando a geração foi cortada de fora; o silêncio desta tentativa e a falha do
         * provedor viram exceção.
         */
        private RespostaDoModelo chamar(ProvedorDeModelo provedor, OpcoesDoPedido opcoes, Duration silencio,
                List<Mensagem> mensagens, String qual) throws IOException, InterruptedException {
            PedidoAoModelo pedido = new PedidoAoModelo(
                    InstrucoesDeSistema.de(new BlocoDeSistema(prompt.sistema(objeto, detalhe), null)),
                    mensagens, null, opcoes);
            // Um corte só desta tentativa (o silêncio), que não pode contaminar a geração inteira: a
            // reserva ainda vai usar o cancelamento geral. O geral desce para a tentativa.
            Cancelamento tentativa = new Cancelamento();
            cancelamento.aoCancelar(() -> tentativa.cancelar(cancelamento.motivo()));
            textoRecebido = false;
            ultimaFalha = "";
            inicioDaResposta.setLength(0);
            chamadas++;
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
                return null;
            }
            if (tentativa.cancelado()) {
                throw new IOException(tentativa.motivo());
            }
            anotarUso(resposta.uso());
            String cauda;
            boolean caudaDescartada;
            synchronized (montando) {
                cauda = buffer.resto();
                int antes = montagem.descartadas();
                publicar(montagem.processar(cauda));
                caudaDescartada = montagem.descartadas() > antes;
            }
            log.info("Holograma '{}' ({}) chamada {}: provedor={} stop_reason={} tokens_saida={} teto={} "
                    + "linha_fim={} peças até aqui={}{}",
                    curto(objeto), detalhe.chave(), qual, provedorUsado,
                    paradaDaApi(resposta.motivo(), provedorUsado), resposta.uso().saida(), opcoes.maxTokens(),
                    montagem.terminou() ? "sim" : "não", montagem.pecas(),
                    caudaDescartada ? ", última linha cortada: " + cortada(cauda) : "");
            return resposta;
        }

        private void anotarUso(RespostaDoModelo.Uso uso) {
            if (uso.entrada() <= 0 && uso.saida() <= 0) return;
            // Prompt inteiro: o que a API cobra como entrada mais o cache, se houver. Somado entre
            // as chamadas, porque a continuação também é paga.
            long entrada = uso.entrada() + uso.cacheEscrito() + uso.cacheLido();
            Long entradaAntes = tokensEntrada;
            Long saidaAntes = tokensSaida;
            tokensEntrada = (entradaAntes == null ? 0 : entradaAntes) + entrada;
            tokensSaida = (saidaAntes == null ? 0 : saidaAntes) + uso.saida();
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
                    publicar(montagem.processar(linha));
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
                    case LIMITE -> publicar(montagem.encerrar());
                    case TEMPO -> publicar(montagem.interromper(TEXTO_TEMPO));
                    case SUBSTITUIDO -> publicar(montagem.interromper(TEXTO_SUBSTITUIDO));
                    default -> { } // O cliente saiu: não há a quem dizer.
                }
            }
            return motivo;
        }

        private String encerrarComErro(String texto) {
            synchronized (montando) {
                publicar(montagem.interromper(texto));
            }
            return "erro";
        }

        /**
         * O que a montagem produziu: guarda meta e peças para a retomada <b>antes</b> de mandar — a
         * peça que não chegou à HUD é justamente a que a retomada tem de reenviar.
         */
        private void publicar(List<SaidaHolograma> saidas) {
            for (SaidaHolograma saida : saidas) {
                if (saida instanceof SaidaHolograma.Meta meta) metaVista = meta;
                else if (saida instanceof SaidaHolograma.Peca peca) pecasVistas.add(peca);
                else if (saida instanceof SaidaHolograma.Fim fim && fim.completo()) guardarNaGaleria();
            }
            enviar(saidas);
        }

        /** O objeto completo vai para a galeria, substituindo o do mesmo pedido. Falha só no log. */
        private void guardarNaGaleria() {
            if (galeria == null || metaVista == null || pecasVistas.size() < GaleriaDeHologramas.MINIMO_DE_PECAS) {
                return;
            }
            naGaleria = galeria.salvar(id, objeto, detalhe, provedorUsado, modeloUsado, metaVista.meta(),
                    pecasVistas.stream().map(SaidaHolograma.Peca::peca).toList());
        }

        private void enviar(List<SaidaHolograma> saidas) {
            for (SaidaHolograma original : saidas) {
                if (clienteFora) {
                    return;
                }
                // A meta e o fim carregam o id, para a HUD poder pedir a continuação; o fim carrega
                // o gasto: quem sabe o provedor e os tokens é esta geração, não a montagem.
                SaidaHolograma saida = switch (original) {
                    case SaidaHolograma.Meta meta -> meta.comId(id);
                    case SaidaHolograma.Fim fim -> fim.comResumo(resumo()).comId(id)
                            .naGaleria(SaidaHolograma.ORIGEM_MODELO, naGaleria ? Boolean.valueOf(galeria.temMiniatura(id)) : null);
                    default -> original;
                };
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
                if (saida instanceof SaidaHolograma.Fim) {
                    fimEnviado = true;
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

        private void registrar(String fim) {
            String primeira = primeiraPeca < 0 ? "-"
                    : segundos(Duration.ofNanos(primeiraPeca - inicio));
            SaidaHolograma.Resumo resumo = resumo();
            String status = montagem.completo() ? SaidaHolograma.COMPLETO
                    : SaidaHolograma.INCOMPLETO + montagem.fim().map(f -> " (" + f.motivo() + ")").orElse("");
            log.info("Holograma '{}' ({}) {}: provedor={}{} 1ª peça em {}, {} peças, {} chamada(s), "
                    + "{} linhas descartadas, {} repetidas, {} avisos {}, tokens={}, custo estimado={}, "
                    + "total {}, status={}, evento fim={}, fim={}",
                    curto(objeto), detalhe.chave(), id,
                    provedorUsado.isEmpty() ? "-" : provedorUsado,
                    modeloUsado.isEmpty() ? "" : " modelo=" + modeloUsado,
                    primeira, montagem.pecas(), chamadas, montagem.descartadas(), montagem.repetidas(),
                    montagem.avisos(), montagem.textosDeAviso(),
                    resumo.tokensEntrada() == null ? "?" : resumo.tokensEntrada() + "/" + resumo.tokensSaida(),
                    resumo.custoUsd() == null ? "-" : String.format(Locale.ROOT, "US$ %.4f", resumo.custoUsd()),
                    segundos(Duration.ofNanos(System.nanoTime() - inicio)), status,
                    fimEnviado ? "enviado" : "não enviado", fim);
            List<String> descartadas = montagem.descartadasBrutas();
            if (!descartadas.isEmpty()) {
                log.info("Holograma '{}' {}: linhas descartadas (até 5): {}", curto(objeto), id, descartadas);
            }
        }
    }

    /**
     * O que a geração diz no {@code fim} incompleto, pelo jeito que a chamada parou: teto de tokens,
     * ou qualquer outra coisa sem a linha de fim (a Anthropic que fechou o stream sem
     * {@code message_stop} volta sem motivo).
     */
    private static String motivoDoCorte(RespostaDoModelo resposta) {
        return resposta.motivo() == RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS ? MOTIVO_TETO
                : MontagemDeHolograma.MOTIVO_SEM_FIM;
    }

    /**
     * O motivo de parada como o provedor o escreve, para o log ser lido junto da documentação de
     * cada um. O neutro junta {@code end_turn} e {@code stop_sequence}; "nenhum" é o stream que
     * acabou antes de o provedor dizer por quê.
     */
    static String paradaDaApi(RespostaDoModelo.MotivoDeParada motivo, String provedor) {
        boolean doLocal = LOCAL.equals(provedor);
        return switch (motivo) {
            case FIM -> doLocal ? "stop" : "end_turn";
            case LIMITE_DE_TOKENS -> doLocal ? "length" : "max_tokens";
            case RECUSA -> "refusal";
            case PAUSA -> "pause_turn";
            case FERRAMENTA -> "tool_use";
            case OUTRO -> "nenhum";
        };
    }

    private static String curto(String objeto) {
        return objeto.length() > 40 ? objeto.substring(0, 40) + "…" : objeto;
    }

    private static String cortada(String linha) {
        String limpa = linha.strip();
        return limpa.length() > TRECHO_DO_LOG ? limpa.substring(0, TRECHO_DO_LOG) + "…" : limpa;
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
