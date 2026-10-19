package dev.jarvis.pc;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A fila de comandos para o PC Windows, em memória.
 *
 * O PC nunca recebe conexão de fora: é ele quem pergunta, por long polling, se tem comando
 * ({@code GET /api/pc/comandos}), executa e posta o resultado. Este serviço é o ponto de encontro
 * entre as duas pontas — a ferramenta {@code controlar_pc} enfileira e espera, o agente consome e
 * responde.
 *
 * <p><b>Nenhum método aqui é {@code synchronized}, e nenhum lock é mantido durante espera
 * bloqueante.</b> Isso não é estilo, é condição de vida: o {@code proximoComando} fica parado até
 * 25 segundos, e se ele segurasse um monitor da classe o {@code enfileirar} travaria junto — como
 * a ferramenta roda dentro do lock do {@code Assistente}, o bot inteiro congelaria 25 s a cada
 * pedido. Por isso a sincronização é toda das próprias coleções concorrentes.
 *
 * <p>A fila vive aqui, e não no {@code PcController}, para a dependência ficar {@code web -> pc}
 * e a camada web não entrar no domínio. É também por isso que não existe porta invertida como a
 * {@code PresencaDaHud}: ali quem sabe o estado mora em {@code dev.jarvis.web}, aqui não.
 */
@Service
public class PcService {

    private static final Logger log = LoggerFactory.getLogger(PcService.class);

    /** Vida do comando. Passado disso ele é descartado em vez de rodar atrasado no PC. */
    static final Duration VALIDADE = Duration.ofSeconds(60);
    /**
     * Sem contato por mais que isto, o PC está desligado ou sem rede. Com o agente em long poll
     * contínuo o contato se renova a cada 25 s no máximo, então 90 s tolera duas voltas perdidas
     * (rede oscilando, agente reiniciando). Mudou a espera do long poll? Mude este número junto.
     */
    static final Duration LIMITE_SEM_CONTATO = Duration.ofSeconds(90);

    private static final int MAX_APPS = 30;
    /** Nome de app aceitável: é ele que vai para o prompt do modelo. */
    private static final String NOME_DE_APP = "[a-z0-9][a-z0-9_.-]{0,31}";

    private record Pendente(ComandoPc comando, Instant expira,
            CompletableFuture<ResultadoPc> resposta) {
    }

    /** Comandos ainda não entregues ao agente, na ordem de chegada. */
    private final BlockingQueue<Pendente> fila = new LinkedBlockingQueue<>();
    /** Todo comando vivo, entregue ou não, para achar pelo id quando o resultado chegar. */
    private final ConcurrentMap<String, Pendente> emAberto = new ConcurrentHashMap<>();

    private volatile Instant ultimoContato;
    private volatile List<String> apps = List.of();

    private final Clock relogio;
    private final OperacoesPcService e2;

    public PcService() {
        this(Clock.systemUTC());
    }

    public PcService(Clock relogio) {
        this.relogio = relogio;
        this.e2 = null;
    }

    @Autowired
    public PcService(OperacoesPcService e2) {
        this.relogio = Clock.systemUTC();
        this.e2 = e2;
    }

    /**
     * Enfileira o comando e devolve o futuro do resultado; quem chama decide quanto espera.
     *
     * <p>Usamos {@code CompletableFuture} e não latch nem {@code SynchronousQueue}: o
     * {@code complete} nunca bloqueia (um agente respondendo depois que a ferramenta desistiu não
     * prende thread nenhuma) e devolve {@code false} na segunda chamada, o que já é a defesa
     * contra POST repetido por retry de rede.
     */
    public CompletableFuture<ResultadoPc> enfileirar(ComandoPc comando) {
        return enfileirar(comando, "", 0);
    }

    public CompletableFuture<ResultadoPc> enfileirar(ComandoPc comando, String canal, long chatId) {
        if (e2 != null && e2.online()) {
            Duration prazo = "criar_arquivo".equals(comando.acao())
                    ? Duration.ofMinutes(3) : VALIDADE;
            return e2.enfileirar(comando, prazo, Duration.ofMinutes(5), canal, chatId);
        }
        Pendente pendente = new Pendente(comando, relogio.instant().plus(
                "criar_arquivo".equals(comando.acao()) ? Duration.ofMinutes(3) : VALIDADE),
                new CompletableFuture<>());
        emAberto.put(comando.id(), pendente);
        fila.add(pendente);
        log.info("Comando {} ({}) na fila do PC", comando.id(), comando.acao());
        return pendente.resposta();
    }

    /**
     * O próximo comando para o agente, esperando até {@code espera} por um — é o long poll.
     *
     * @param espera       quanto segurar a requisição antes de devolver vazio (204)
     * @param headerApps   o {@code X-Pc-Apps} da requisição, ou nulo quando não veio
     */
    public Optional<ComandoPc> proximoComando(Duration espera, String headerApps) {
        // No começo, não no fim: a prova de vida é a chegada da requisição. Marcar depois do
        // poll atrasaria o "último contato" em até 25 segundos.
        registrarContato();
        registrarApps(headerApps);

        long restante = espera.toNanos();
        while (restante > 0) {
            long inicio = System.nanoTime();
            Pendente pendente;
            try {
                pendente = fila.poll(restante, TimeUnit.NANOSECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
            if (pendente == null) {
                return Optional.empty();
            }
            if (relogio.instant().isBefore(pendente.expira())) {
                return Optional.of(pendente.comando());
            }
            // Venceu na fila (o PC esteve fora). Não entregamos, e o que sobra do orçamento
            // continua descontado — senão uma fila de vencidos esticaria o long poll sem fim.
            vencer(pendente);
            restante -= System.nanoTime() - inicio;
        }
        return Optional.empty();
    }

    /**
     * Guarda o resultado que o agente postou e acorda quem estava esperando.
     *
     * @return falso quando o id é desconhecido, já venceu, ou o resultado é repetido
     */
    public boolean concluir(ResultadoPc resultado) {
        registrarContato();
        Pendente pendente = emAberto.remove(resultado.comandoId());
        if (pendente == null) {
            return false;
        }
        if (!relogio.instant().isBefore(pendente.expira())) {
            pendente.resposta().complete(ResultadoPc.expirado(pendente.comando().id()));
            return false;
        }
        if (resultado.dados() != null && !List.of("listar_arquivos", "ler_arquivo")
                .contains(pendente.comando().acao())) {
            pendente.resposta().complete(new ResultadoPc(resultado.comandoId(), false,
                    "dados inesperados para esta ação", null, null));
            return false;
        }
        log.info("Comando {} ({}) respondido pelo PC: ok={}", pendente.comando().id(),
                pendente.comando().acao(), resultado.ok());
        return pendente.resposta().complete(resultado);
    }

    public boolean aguardandoArquivo(String id) {
        if (e2 != null && e2.estado(id).isPresent()) {
            var estado = e2.estado(id).orElseThrow();
            return List.of("criar_arquivo", "aplicar_edicao", "aplicar_desfazer")
                    .contains(estado.acao())
                    && List.of("recebido", "executando").contains(estado.estado());
        }
        Pendente pendente = emAberto.get(id);
        return pendente != null && "criar_arquivo".equals(pendente.comando().acao())
                && relogio.instant().isBefore(pendente.expira());
    }

    /** Cancela a espera quando a ferramenta já devolveu resposta ao usuário. */
    public void cancelar(String id) {
        if (e2 != null && e2.estado(id).isPresent()) { e2.cancelar(id); return; }
        Pendente pendente = emAberto.get(id);
        if (pendente != null) vencer(pendente);
    }
    public void pedirAviso(String id) {
        if (e2 != null) e2.pedirAviso(id);
    }
    public Optional<OperacoesPcService.Retrato> estadoE2(String id) {
        return e2 == null ? Optional.empty() : e2.estado(id);
    }
    public Optional<Path> caminhoArquivoE2(String id) {
        return e2 != null && e2.estado(id).isPresent()
                ? Optional.of(e2.arquivoDeUpload(id)) : Optional.empty();
    }

    /**
     * Descarta o que passou da validade.
     *
     * <p>A ferramenta desiste antes (30 s), então esta varredura <b>nunca</b> é o que a desbloqueia.
     * Ela serve a outras duas coisas: a fila não acumular enquanto o PC está desligado, e um
     * resultado que chegue em t=70 s ser recusado com 404 em vez de mandado ao Telegram muito
     * depois do pedido.
     */
    @Scheduled(fixedRate = 10_000)
    public void expirarPendentes() {
        Instant agora = relogio.instant();
        for (Pendente pendente : List.copyOf(emAberto.values())) {
            if (!agora.isBefore(pendente.expira())) {
                vencer(pendente);
            }
        }
    }

    /** Se o PC deu sinal de vida dentro do limite. */
    public boolean online() {
        if (e2 != null && e2.online()) return true;
        Instant visto = ultimoContato;
        return visto != null
                && Duration.between(visto, relogio.instant()).compareTo(LIMITE_SEM_CONTATO) < 0;
    }

    /** Quanto tempo desde o último contato, ou vazio se o agente nunca se conectou. */
    public Optional<Duration> desdeOUltimoContato() {
        Instant visto = ultimoContato;
        return visto == null ? Optional.empty() : Optional.of(Duration.between(visto, relogio.instant()));
    }

    /** Quantos comandos estão vivos (na fila ou entregues sem resposta). */
    public int naFila() {
        return emAberto.size();
    }

    /** Os nomes de app que o agente diz aceitar. Lista vazia = nenhum, ou nunca reportou. */
    public List<String> appsConhecidos() {
        return apps;
    }

    private void registrarContato() {
        this.ultimoContato = relogio.instant();
    }

    /**
     * Guarda os nomes de app que o agente reporta no {@code X-Pc-Apps} de cada long poll.
     *
     * <p>A higienização não é zelo decorativo: estes nomes entram no schema e na
     * {@code descricao()} da ferramenta, isto é, <b>vão direto para o prompt do Claude</b>. Um
     * nome com quebra de linha ou com "ignore as instruções anteriores" seria injeção de prompt
     * por cabeçalho HTTP, e um cabeçalho de 10 KB engordaria todo pedido em tokens.
     *
     * <p>Assimetria de propósito: cabeçalho <b>ausente</b> é ausência de informação e mantém a
     * lista anterior (um agente antigo não apaga o enum no meio da sessão); cabeçalho
     * <b>presente e vazio</b> é informação explícita de que não há app liberado.
     */
    private void registrarApps(String headerApps) {
        if (headerApps == null) {
            return;
        }
        this.apps = Arrays.stream(headerApps.split(","))
                .map(nome -> nome.trim().toLowerCase(Locale.ROOT))
                .filter(nome -> nome.matches(NOME_DE_APP))
                .distinct()
                .limit(MAX_APPS)
                .toList();
    }

    /**
     * Tira o comando de circulação e responde a quem espera que ele venceu.
     *
     * <p>O {@code remove} condicional é o que garante que só um caminho ganha quando a varredura
     * e o {@code concluir} correm juntos; o {@code complete} do perdedor é inofensivo.
     */
    private void vencer(Pendente pendente) {
        if (emAberto.remove(pendente.comando().id(), pendente)) {
            fila.remove(pendente);
            pendente.resposta().complete(ResultadoPc.expirado(pendente.comando().id()));
            log.info("Comando {} ({}) venceu sem resposta do PC", pendente.comando().id(),
                    pendente.comando().acao());
        }
    }
}
