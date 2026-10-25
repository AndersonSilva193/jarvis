package dev.jarvis.diario;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * O que o Jarvis fez — sozinho e depois de um "sim" —, um JSON por dia, 30 dias.
 *
 * <p><b>Por que existe:</b> com mais autonomia, o dono precisa poder auditar o que foi feito sem
 * ter estado olhando a conversa na hora. O aviso do anotador sai numa linha da resposta seguinte e
 * vai embora; o resto não deixava rastro nenhum. Memória — e arquivo — que muda calado é memória
 * que o dono não consegue corrigir.
 *
 * <p>O desenho é o do {@link dev.jarvis.custo.MedidorDeCusto}, de propósito, incluindo as razões:
 *
 * <ul>
 *   <li><b>Gravação write-behind</b> numa thread virtual única. O registro acontece dentro do
 *       {@code ReentrantLock} justo do {@code Assistente}; uma troca atômica no caminho crítico
 *       poria o bot inteiro atrás do disco da VPS. Uma thread só porque as gravações do mesmo dia
 *       têm de sair na ordem.</li>
 *   <li><b>Virada do dia conferida preguiçosamente</b> em {@link #registrar}, e não só pelo cron de
 *       meia-noite: o container pode estar parado às 00:00, e aí o cron nunca roda.</li>
 *   <li><b>Dia sem arquivo é dia omitido</b>, não dia zerado: zero ali afirmaria "não fiz nada"
 *       onde a verdade é "eu estava desligado".</li>
 *   <li><b>Registrar nunca lança.</b> O diário é a testemunha da ação, não o guarda dela — uma
 *       falha de disco não pode desfazer uma edição que já aconteceu no PC.</li>
 * </ul>
 */
@Component
public class DiarioDeAcoes {

    private static final Logger log = LoggerFactory.getLogger(DiarioDeAcoes.class);

    /**
     * Quantos dias ficam no volume.
     *
     * Constante na classe, e não configuração, pela mesma regra do {@code MedidorDeCusto} e do
     * {@code BriefingService}: não valia mais um componente num record construído por posição em
     * dezenas de testes para um número que ninguém vai querer mexer.
     */
    static final int DIAS_GUARDADOS = 30;

    private static final long ESPERA_DE_FECHAMENTO_S = 5;

    private final JarvisProperties props;
    private final Clock relogio;
    private final DiarioEmArquivo arquivo;
    private final ExecutorService gravacoes;

    /** Protege o dia corrente e os retratos pendentes; a gravação roda fora dele. */
    private final Object trava = new Object();
    /** Último retrato ainda não salvo de cada data, inclusive um dia já encerrado. */
    private final Map<LocalDate, DiaDoDiario> pendentes = new LinkedHashMap<>();

    private DiaDoDiario dia;

    @Autowired
    public DiarioDeAcoes(JarvisProperties props, JsonMapper json) {
        this(props, json, Clock.systemUTC(), props.pastaDoDiario());
    }

    /**
     * O relógio e a pasta entram por aqui nos testes, para o dia e o disco serem previsíveis.
     *
     * Público, e não de pacote como o do {@code MedidorDeCusto}, porque o teste da ferramenta
     * {@code acoes_do_jarvis} mora em {@code dev.jarvis.ferramentas} e precisa de um relógio falso
     * para conseguir afirmar algo sobre "ontem". É costura de teste, como o {@link #aguardar()}.
     */
    public DiarioDeAcoes(JarvisProperties props, JsonMapper json, Clock relogio, Path pasta) {
        this.props = props;
        this.relogio = relogio;
        this.arquivo = new DiarioEmArquivo(pasta, json, props.zona());
        this.gravacoes = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("diario").factory());
    }

    // ---------------------------------------------------------------- registro

    /**
     * Anota uma ação e volta na hora, sem esperar o disco.
     *
     * <p>Não lança <b>nunca</b>: quem chama acabou de mexer num arquivo do dono, e o diário é a
     * testemunha disso, não a condição para que aconteça.
     */
    public void registrar(TipoDeAcao tipo, String descricao, OrigemDaAcao origem) {
        try {
            AcaoRegistrada acao = new AcaoRegistrada(relogio.instant(), tipo, descricao, origem);
            synchronized (trava) {
                virarSePrecisar(hojeLocal());
                if (!dia.somar(acao) && dia.descartadas() == 1) {
                    log.warn("Diário de {}: passou de {} ações no dia; as próximas só serão "
                            + "contadas", dia.dia(), DiaDoDiario.MAX_ACOES);
                }
                pendentes.put(dia.dia(), dia.copia());
            }
            naFila(false, this::gravarPendentes);
        } catch (RuntimeException e) {
            log.warn("Não consegui anotar no diário a ação {}: {}", tipo, e.getMessage());
        }
    }

    /** Atalho de quem registra uma ação de ferramenta, traduzindo o canal do {@code Ouvinte}. */
    public void registrarDoCanal(TipoDeAcao tipo, String descricao, String canal) {
        registrar(tipo, descricao, OrigemDaAcao.porCanal(canal));
    }

    /**
     * Troca o acumulador quando a data local mudou. O retrato anterior continua na fila, mesmo se
     * uma gravação falhar e o relógio já estiver no dia seguinte.
     */
    private void virarSePrecisar(LocalDate hoje) {
        if (dia != null && dia.dia().equals(hoje)) {
            return;
        }
        if (dia != null && !dia.vazio()) {
            pendentes.put(dia.dia(), dia.copia());
        }
        dia = arquivo.carregar(hoje);
    }

    private LocalDate hojeLocal() {
        return LocalDate.ofInstant(relogio.instant(), props.zona());
    }

    // ---------------------------------------------------------------- leitura

    /**
     * As ações de hoje e de ontem.
     *
     * Existem para quem lê o diário <b>não ter a própria noção de "hoje"</b>: o dia é o do relógio
     * e do fuso deste componente, e um {@code LocalDate.now()} do lado de quem pergunta divergiria
     * dele — foi exatamente o que o teste da ferramenta pegou.
     */
    public Dia deHoje() {
        return doDia(hojeLocal());
    }

    public Dia deOntem() {
        return doDia(hojeLocal().minusDays(1));
    }

    /** O fuso em que este diário conta os dias, para quem precisa formatar a hora de uma ação. */
    public ZoneId zona() {
        return props.zona();
    }

    /** As ações de um dia, em ordem cronológica. */
    public Dia doDia(LocalDate quando) {
        List<DiaDoDiario> estados = estadosDe(quando, quando);
        if (estados.isEmpty()) {
            return new Dia(quando, quando, List.of(), 0);
        }
        DiaDoDiario estado = estados.getFirst();
        return new Dia(quando, quando, List.copyOf(estado.acoes()), estado.descartadas());
    }

    /** As ações dos últimos {@code dias} dias, hoje incluído, em ordem cronológica. */
    public Dia periodo(int dias) {
        int quantos = Math.clamp(dias, 1, DIAS_GUARDADOS);
        LocalDate hoje = hojeLocal();
        LocalDate inicio = hoje.minusDays(quantos - 1L);
        List<AcaoRegistrada> acoes = new ArrayList<>();
        int descartadas = 0;
        for (DiaDoDiario estado : estadosDe(inicio, hoje)) {
            acoes.addAll(estado.acoes());
            descartadas += estado.descartadas();
        }
        return new Dia(inicio, hoje, List.copyOf(acoes), descartadas);
    }

    /**
     * As ações <b>autônomas</b> desde o último briefing, contadas por tipo — e avança o marcador.
     *
     * <p>Só as autônomas porque a pergunta que a seção responde é "o que fiz sozinho": o que o dono
     * aprovou ele já sabe, respondeu "Posso?" dois minutos antes.
     *
     * <p>Sem marcador — primeira execução, ou marcador ilegível — a janela começa no <b>início do
     * dia de hoje</b>. Senão uma instalação nova despejaria trinta dias de histórico na primeira
     * manhã, o que não é "o que fiz desde o último briefing".
     */
    public Map<TipoDeAcao, Integer> autonomasDesdeOUltimoBriefing() {
        Instant agora = relogio.instant();
        // O marcador significa "já contei tudo ATÉ AQUI, inclusive", então a janela nova é o que
        // vem estritamente depois dele. Incluir o próprio instante faria a ação registrada no
        // mesmo instante do briefing ser contada de novo no briefing seguinte.
        Instant desde = arquivo.marcador().orElseGet(this::instanteAntesDeHoje);
        if (desde.isAfter(agora)) {
            // Relógio para trás, ou marcador do futuro: conta o dia e segue.
            desde = instanteAntesDeHoje();
        }

        Map<TipoDeAcao, Integer> contagem = new EnumMap<>(TipoDeAcao.class);
        LocalDate inicio = LocalDate.ofInstant(desde, props.zona());
        for (DiaDoDiario estado : estadosDe(inicio, hojeLocal())) {
            for (AcaoRegistrada acao : estado.acoes()) {
                if (acao.tipo().autonoma() && acao.instante().isAfter(desde)
                        && !acao.instante().isAfter(agora)) {
                    contagem.merge(acao.tipo(), 1, Integer::sum);
                }
            }
        }

        Instant ate = agora;
        naFila(false, () -> {
            try {
                arquivo.salvarMarcador(ate);
            } catch (IOException | RuntimeException e) {
                // Marcador que não grava faz a próxima seção repetir a contagem. Repetir é
                // chato; perder o aviso de que o Jarvis mexeu num arquivo é pior.
                log.warn("Não consegui gravar o marcador do briefing: {}", e.getMessage());
            }
        });
        return contagem;
    }

    /**
     * O instante imediatamente anterior ao começo de hoje, no fuso configurado.
     *
     * É o "marcador que não existe": a janela da primeira execução cobre o dia de hoje inteiro,
     * inclusive uma ação gravada exatamente à meia-noite, e nada de antes dele.
     */
    private Instant instanteAntesDeHoje() {
        return hojeLocal().atStartOfDay(props.zona()).toInstant().minusNanos(1);
    }

    /**
     * Os dias com movimento no intervalo, em ordem crescente.
     *
     * Hoje sai da <b>memória</b>, não do disco: a gravação pendente pode não ter chegado, e quem
     * acabou de pedir uma edição espera vê-la no diário.
     */
    private List<DiaDoDiario> estadosDe(LocalDate inicio, LocalDate fim) {
        Map<LocalDate, DiaDoDiario> emMemoria = new LinkedHashMap<>();
        synchronized (trava) {
            pendentes.forEach((data, estado) -> emMemoria.put(data, estado.copia()));
            if (dia != null) {
                emMemoria.put(dia.dia(), dia.copia());
            }
        }
        List<DiaDoDiario> estados = new ArrayList<>();
        for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
            DiaDoDiario retrato = emMemoria.get(d);
            if (retrato != null) {
                if (!retrato.vazio()) {
                    estados.add(retrato);
                }
            } else if (arquivo.existe(d)) {
                DiaDoDiario doDisco = arquivo.carregar(d);
                if (!doDisco.vazio()) {
                    estados.add(doDisco);
                }
            }
        }
        return estados;
    }

    // ---------------------------------------------------------------- disco e dia

    /**
     * Fecha o dia que acabou e varre o que passou do prazo.
     *
     * O acumulador vai a null em vez de já virar para hoje: o próximo registro carrega o dia certo
     * sozinho, e num dia sem nenhuma ação não se cria arquivo vazio.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "${jarvis.fuso-horario}")
    public void virarODia() {
        DiaDoDiario saindo;
        synchronized (trava) {
            // Uma ação registrada logo após a meia-noite pode ter aberto o dia novo antes do cron.
            saindo = dia != null && dia.dia().isBefore(hojeLocal()) ? dia : null;
            if (saindo != null && !saindo.vazio()) {
                pendentes.put(saindo.dia(), saindo.copia());
            }
            if (saindo != null) {
                dia = null;
            }
        }
        if (saindo != null && !saindo.vazio()) {
            naFila(false, this::gravarPendentes);
            log.info("Diário em {}: {} ação(ões) registrada(s){}", saindo.dia(),
                    saindo.acoes().size(),
                    saindo.descartadas() == 0 ? "" : " e " + saindo.descartadas() + " descartada(s)");
        }
        expurgar();
    }

    /**
     * Varre os dias velhos na partida, além da meia-noite.
     *
     * Um container desligado quarenta dias volta com arquivos vencidos, e ninguém vai esperar a
     * próxima meia-noite para limpá-los. Fora do construtor de propósito: I/O num volume Docker
     * durante a criação do bean atrasa a subida do contexto.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void naPartida() {
        expurgar();
    }

    private void expurgar() {
        naFila(false, () -> {
            LocalDate maisAntigo = hojeLocal().minusDays(DIAS_GUARDADOS - 1L);
            synchronized (trava) {
                pendentes.keySet().removeIf(data -> data.isBefore(maisAntigo));
            }
            int apagados = arquivo.expurgar(maisAntigo);
            if (apagados > 0) {
                log.info("Diário: {} dia(s) acima de {} dias apagados", apagados, DIAS_GUARDADOS);
            }
        });
    }

    /**
     * Rede de segurança da gravação: existe para o retrato que falhou com {@link IOException}
     * (volume cheio, por exemplo) ter outra chance sem esperar a meia-noite.
     */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void gravarPendente() {
        naFila(false, this::gravarPendentes);
    }

    /** A fila serializa as gravações; uma falha mantém a data certa para a próxima tentativa. */
    private void gravarPendentes() {
        List<DiaDoDiario> retratos;
        synchronized (trava) {
            retratos = new ArrayList<>(pendentes.values());
        }
        for (DiaDoDiario retrato : retratos) {
            try {
                arquivo.salvar(retrato);
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui gravar o diário de {}: {}", retrato.dia(), e.getMessage());
                continue;
            }
            synchronized (trava) {
                if (pendentes.get(retrato.dia()) == retrato) {
                    pendentes.remove(retrato.dia());
                }
            }
        }
    }

    private void naFila(boolean esperar, Runnable trabalho) {
        try {
            var futuro = gravacoes.submit(trabalho);
            if (esperar) {
                futuro.get(ESPERA_DE_FECHAMENTO_S, TimeUnit.SECONDS);
            }
        } catch (RejectedExecutionException desligando) {
            if (esperar) {
                trabalho.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.warn("Trabalho do diário não terminou: {}", e.getMessage());
        }
    }

    /** Espera a fila esvaziar, gravando o que estiver pendente. Existe para os testes. */
    public void aguardar() {
        naFila(true, this::gravarPendentes);
    }

    /** Grava o que falta antes de o processo sair, para a última ação não se perder. */
    @PreDestroy
    public void fechar() {
        naFila(false, this::gravarPendentes);
        gravacoes.shutdown();
        try {
            if (!gravacoes.awaitTermination(ESPERA_DE_FECHAMENTO_S, TimeUnit.SECONDS)) {
                log.warn("Gravação do diário não terminou em {} s; seguindo com o desligamento",
                        ESPERA_DE_FECHAMENTO_S);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- retrato público

    /**
     * As ações de um intervalo, em ordem cronológica.
     *
     * @param descartadas quantas não couberam no teto do dia. Maior que zero significa que a lista
     *                    está incompleta, e quem fala tem de dizer isso.
     */
    public record Dia(LocalDate de, LocalDate ate, List<AcaoRegistrada> acoes, int descartadas) {

        public Dia {
            acoes = acoes == null ? List.of() : List.copyOf(acoes);
        }

        public boolean vazio() {
            return acoes.isEmpty();
        }

        /** Só o que foi feito sem perguntar. */
        public List<AcaoRegistrada> autonomas() {
            return acoes.stream().filter(acao -> acao.tipo().autonoma()).toList();
        }
    }
}
