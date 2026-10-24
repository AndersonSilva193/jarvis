package dev.jarvis.custo;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
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
import dev.jarvis.telegram.TelegramClient;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quanto o Jarvis gastou hoje, por origem e por modelo — e o aviso quando passa do teto.
 *
 * <p><b>O teto avisa; nunca bloqueia.</b> Um Jarvis que se recusa a responder porque a conta do dia
 * subiu é pior que a conta. O que ele faz é mandar uma mensagem no Telegram ao cruzar 80% e outra
 * ao cruzar 100%, uma vez cada por dia.
 *
 * <p><b>Preço não mora no código</b>, mora em {@code CUSTO_PRECOS} — veja {@link TabelaDePrecos}.
 * Modelo sem preço configurado continua tendo os tokens contados, mas não vira dólar: o dia sai
 * marcado como incompleto e um WARN por modelo por dia diz qual nome falta no {@code .env}.
 *
 * <p>A gravação é <b>write-behind</b>, numa thread virtual própria, como em
 * {@code HistoricoEmArquivo}: um turno dispara até oito chamadas ao modelo segurando o
 * {@code ReentrantLock} justo do {@code Assistente}, e oito trocas atômicas no caminho crítico
 * poriam o bot inteiro atrás do disco da VPS. Escritas em rajada usam o último retrato pendente
 * de cada data, e o {@link #aguardar()} é o ponto onde os testes esperam a fila.
 *
 * <p>A virada do dia é conferida <b>preguiçosamente</b> em {@link #registrar}, e não só pelo cron
 * de meia-noite: o container pode estar parado às 00:00, e aí o cron nunca roda.
 */
@Component
public class MedidorDeCusto {

    private static final Logger log = LoggerFactory.getLogger(MedidorDeCusto.class);

    /**
     * Quantos dias de diário ficam no volume.
     *
     * Constante na classe, e não configuração, pela mesma regra de {@code BriefingService} e
     * {@code PcService}: não valia mais um componente num record construído por posição em dezenas
     * de testes para um número que ninguém vai querer mexer.
     */
    static final int DIAS_GUARDADOS = 90;

    /** A fatia do teto que dispara o primeiro aviso, enquanto ainda dá para fazer alguma coisa. */
    private static final double PRIMEIRO_AVISO = 0.80;

    private static final long ESPERA_DE_FECHAMENTO_S = 5;

    private final JarvisProperties props;
    private final TelegramClient telegram;
    private final Clock relogio;
    private final CustoDiarioEmArquivo arquivo;
    private final TabelaDePrecos precos;
    private final ExecutorService gravacoes;

    /** Protege acumulador e retratos pendentes; a gravação e o Telegram rodam fora dele. */
    private final Object trava = new Object();
    /** Último retrato ainda não salvo de cada data, inclusive um dia já encerrado. */
    private final Map<LocalDate, EstadoDoDia> pendentes = new LinkedHashMap<>();
    /** Alertas só saem depois que os seus sinalizadores foram salvos. */
    private final Map<LocalDate, List<String>> avisosPendentes = new LinkedHashMap<>();

    private EstadoDoDia dia;

    @Autowired
    public MedidorDeCusto(JarvisProperties props, TelegramClient telegram, JsonMapper json) {
        this(props, telegram, json, Clock.systemUTC(), props.pastaDeCusto());
    }

    /** O relógio e a pasta entram por aqui nos testes, para o dia e o disco serem previsíveis. */
    MedidorDeCusto(JarvisProperties props, TelegramClient telegram, JsonMapper json, Clock relogio,
            Path pasta) {
        this.props = props;
        this.telegram = telegram;
        this.relogio = relogio;
        this.arquivo = new CustoDiarioEmArquivo(pasta, json, props.zona());
        this.precos = TabelaDePrecos.analisar(props.precosDeCusto(),
                props.precosDeContextoLongo(), props.precoDaBuscaWeb());
        // Uma thread só: as gravações do mesmo dia têm de sair na ordem, ou um retrato antigo
        // sobrescreveria o recente.
        this.gravacoes = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("custo").factory());
        if (precos.porModelo().isEmpty()) {
            log.info("Custo: nenhum preço em CUSTO_PRECOS; vou contar tokens, mas não dólares.");
        }
    }

    // ---------------------------------------------------------------- registro

    /**
     * Soma um consumo ao dia sem esperar a gravação nem o Telegram. Na primeira chamada de um dia,
     * carrega o arquivo daquele dia para continuar a conta após um restart.
     *
     * <p>A busca na web é cobrada por conta própria, fora do preço do modelo — então um modelo sem
     * preço ainda soma o custo das buscas que fez, e só os tokens dele ficam de fora.
     */
    public void registrar(Origem origem, String modelo, long entrada, long saida,
            long cacheEscrito, long cacheLido, long buscasWeb) {
        Origem deOnde = origem == null ? Origem.CONVERSA : origem;
        String nome = modelo == null || modelo.isBlank() ? "desconhecido" : modelo.trim();
        boolean semPreco = precos.precoDe(nome) == null;
        double custo = precos.custo(nome, entrada, saida, cacheEscrito, cacheLido, buscasWeb);

        String aviso;
        boolean primeiroSemPreco;
        synchronized (trava) {
            virarSePrecisar(hojeLocal());
            primeiroSemPreco = semPreco && !dia.conheceSemPreco(nome);
            dia.somar(deOnde, nome, entrada, saida, cacheEscrito, cacheLido, buscasWeb, custo, semPreco);
            aviso = avaliarTeto();
            pendentes.put(dia.dia(), dia.copia());
            if (aviso != null) {
                avisosPendentes.computeIfAbsent(dia.dia(), data -> new ArrayList<>()).add(aviso);
            }
        }

        if (primeiroSemPreco) {
            log.warn("Custo: sem preço configurado para o modelo '{}' em CUSTO_PRECOS; conto os "
                    + "tokens, mas o custo do dia sai incompleto.", nome);
        }
        naFila(false, this::gravarPendentes);
    }

    /**
     * Troca o acumulador quando a data local mudou. O retrato anterior continua na fila, mesmo
     * se uma gravação falhar e o relógio já estiver no dia seguinte.
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

    // ---------------------------------------------------------------- teto

    /**
     * Decide se este consumo cruzou um dos dois limites. Roda com a trava na mão e só devolve o
     * texto: a fila grava os sinalizadores antes de mandar a mensagem, fora do turno do usuário.
     *
     * <p>Um salto que passe de 0 para além de 100% manda <b>só</b> a mensagem dos 100%: o aviso
     * dos 80% existe para chegar antes, e chegar junto com o outro é ruído.
     */
    private String avaliarTeto() {
        double teto = props.tetoDiarioDeCusto();
        if (teto <= 0) {
            return null;
        }
        if (dia.tetoGravado != teto) {
            // O dono acabou de mexer no teto: os avisos do teto antigo não valem para o novo.
            dia.tetoGravado = teto;
            dia.avisou80 = false;
            dia.avisou100 = false;
        }
        double gasto = dia.totalUsd();
        if (gasto >= teto && !dia.avisou100) {
            dia.avisou100 = true;
            dia.avisou80 = true;
            return "Senhor, passei do teto de " + Dinheiro.usd(teto) + ": já são "
                    + Dinheiro.usd(gasto) + " hoje. Sigo trabalhando — é só um aviso.";
        }
        if (gasto >= teto * PRIMEIRO_AVISO && !dia.avisou80) {
            dia.avisou80 = true;
            return "Senhor, já gastei " + Dinheiro.usd(gasto) + " hoje, "
                    + percentual(gasto, teto) + "% do teto de " + Dinheiro.usd(teto) + ".";
        }
        return null;
    }

    private static int percentual(double gasto, double teto) {
        return teto <= 0 ? 0 : (int) Math.floor(gasto * 100 / teto);
    }

    /** O guarda é o mesmo do {@code MonitorDeCotaVoz}: sem dono configurado, ninguém é avisado. */
    private void avisar(String texto) {
        long chat = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        if (chat == 0) {
            return;
        }
        telegram.enviarMensagem(chat, texto);
    }

    // ---------------------------------------------------------------- leitura

    /** O gasto de hoje. */
    public Relatorio hoje() {
        return periodo(1);
    }

    /**
     * O gasto dos últimos {@code dias} dias, hoje incluído.
     *
     * Dia sem arquivo é dia em que o Jarvis estava desligado, e entra como ausente, não como zero.
     */
    public Relatorio periodo(int dias) {
        int quantos = Math.clamp(dias, 1, DIAS_GUARDADOS);
        LocalDate hoje = hojeLocal();
        LocalDate inicio = hoje.minusDays(quantos - 1L);

        Map<Origem, Double> porOrigem = new LinkedHashMap<>();
        Map<String, Double> porModelo = new LinkedHashMap<>();
        List<String> semPreco = new ArrayList<>();
        double total = 0;
        long buscas = 0;
        for (EstadoDoDia estado : estadosDe(inicio, hoje)) {
            estado.porOrigem().forEach((origem, uso) ->
                    porOrigem.merge(origem, uso.custoUsd, Double::sum));
            estado.porModelo().forEach((modelo, uso) ->
                    porModelo.merge(modelo, uso.custoUsd, Double::sum));
            for (String modelo : estado.semPreco()) {
                if (!semPreco.contains(modelo)) {
                    semPreco.add(modelo);
                }
            }
            total += estado.totalUsd();
            buscas += estado.buscasWeb();
        }
        double teto = props.tetoDiarioDeCusto();
        return new Relatorio(inicio, hoje, total, buscas, ordenar(porOrigem), ordenar(porModelo),
                List.copyOf(semPreco), teto, quantos == 1 ? percentual(total, teto) : 0);
    }

    /**
     * Um ponto por dia, do mais antigo para o mais recente — o que o gráfico de custo vai consumir.
     *
     * Dia sem arquivo é <b>omitido</b>, não preenchido com zero: zero ali desenharia "não gastei
     * nada" onde a verdade é "eu estava desligado". Quem desenha tem o intervalo e preenche se
     * quiser um eixo contínuo.
     */
    public List<DiaDeCusto> serieDiaria(int dias) {
        int quantos = Math.clamp(dias, 1, DIAS_GUARDADOS);
        LocalDate hoje = hojeLocal();
        List<DiaDeCusto> serie = new ArrayList<>();
        for (EstadoDoDia estado : estadosDe(hoje.minusDays(quantos - 1L), hoje)) {
            long entrada = 0;
            long saida = 0;
            long cacheEscrito = 0;
            long cacheLido = 0;
            for (Uso uso : estado.porModelo().values()) {
                entrada += uso.entrada;
                saida += uso.saida;
                cacheEscrito += uso.cacheEscrito;
                cacheLido += uso.cacheLido;
            }
            Map<Origem, Double> porOrigem = new LinkedHashMap<>();
            estado.porOrigem().forEach((origem, uso) -> porOrigem.put(origem, uso.custoUsd));
            serie.add(new DiaDeCusto(estado.dia(), estado.totalUsd(), entrada, saida, cacheEscrito,
                    cacheLido, estado.buscasWeb(), !estado.semPreco().isEmpty(), porOrigem));
        }
        return serie;
    }

    /**
     * Os dias com movimento no intervalo, em ordem crescente.
     *
     * Hoje sai da <b>memória</b>, não do disco: a gravação pendente pode não ter chegado, e o dono
     * que acabou de mandar uma mensagem espera vê-la na conta.
     */
    private List<EstadoDoDia> estadosDe(LocalDate inicio, LocalDate fim) {
        Map<LocalDate, EstadoDoDia> emMemoria = new LinkedHashMap<>();
        synchronized (trava) {
            pendentes.forEach((data, estado) -> emMemoria.put(data, estado.copia()));
            if (dia != null) {
                emMemoria.put(dia.dia(), dia.copia());
            }
        }
        List<EstadoDoDia> estados = new ArrayList<>();
        for (LocalDate d = inicio; !d.isAfter(fim); d = d.plusDays(1)) {
            EstadoDoDia retrato = emMemoria.get(d);
            if (retrato != null) {
                if (!retrato.vazio()) {
                    estados.add(retrato);
                }
            } else if (arquivo.existe(d)) {
                EstadoDoDia doDisco = arquivo.carregar(d);
                if (!doDisco.vazio()) {
                    estados.add(doDisco);
                }
            }
        }
        return estados;
    }

    /** Do mais caro para o mais barato: a manchete é o que está pesando na conta. */
    private static <C> Map<C, Double> ordenar(Map<C, Double> bruto) {
        Map<C, Double> ordenado = new LinkedHashMap<>();
        bruto.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .forEach(e -> ordenado.put(e.getKey(), e.getValue()));
        return ordenado;
    }

    // ---------------------------------------------------------------- disco e dia

    /**
     * Fecha o dia que acabou e varre o que passou do prazo.
     *
     * O acumulador vai a null em vez de já virar para hoje: a próxima chamada ao modelo carrega o
     * dia certo sozinha, e num dia sem nenhuma chamada não se cria arquivo vazio.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "${jarvis.fuso-horario}")
    public void virarODia() {
        EstadoDoDia saindo;
        synchronized (trava) {
            // Um registro feito logo após a meia-noite pode ter aberto o dia novo antes do cron.
            // Nesse caso, o cron não pode fechá-lo e recarregar do disco ainda desatualizado.
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
            log.info("Custo em {}: {} no total, {} busca(s) na web{}", saindo.dia(),
                    Dinheiro.usd(saindo.totalUsd()), saindo.buscasWeb(),
                    saindo.semPreco().isEmpty() ? "" : " (sem preço para " + saindo.semPreco() + ")");
        }
        expurgar();
    }

    /**
     * Varre os dias velhos na partida, além da meia-noite.
     *
     * Um container desligado cem dias volta com cem arquivos vencidos, e ninguém vai esperar a
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
                avisosPendentes.keySet().removeIf(data -> data.isBefore(maisAntigo));
            }
            int apagados = arquivo.expurgar(maisAntigo);
            if (apagados > 0) {
                log.info("Custo: {} dia(s) acima de {} dias apagados do diário", apagados, DIAS_GUARDADOS);
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
        List<EstadoDoDia> retratos;
        synchronized (trava) {
            retratos = new ArrayList<>(pendentes.values());
        }
        for (EstadoDoDia retrato : retratos) {
            if (!gravar(retrato)) {
                continue;
            }
            List<String> alertas = List.of();
            synchronized (trava) {
                if (pendentes.get(retrato.dia()) == retrato) {
                    pendentes.remove(retrato.dia());
                    alertas = avisosPendentes.remove(retrato.dia());
                }
            }
            if (alertas != null) {
                for (String alerta : alertas) {
                    try {
                        avisar(alerta);
                    } catch (RuntimeException e) {
                        log.warn("Não consegui avisar o teto de custo no Telegram: {}", e.getMessage());
                    }
                }
            }
        }
    }

    private boolean gravar(EstadoDoDia estado) {
        try {
            arquivo.salvar(estado);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui gravar o custo de {}: {}", estado.dia(), e.getMessage());
            return false;
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
            log.warn("Trabalho do diário de custo não terminou: {}", e.getMessage());
        }
    }

    /** Espera a fila esvaziar, gravando o que estiver pendente. Existe para os testes. */
    public void aguardar() {
        naFila(true, this::gravarPendentes);
    }

    /** Grava o que falta antes de o processo sair, para o último consumo não se perder. */
    @PreDestroy
    public void fechar() {
        naFila(false, this::gravarPendentes);
        gravacoes.shutdown();
        try {
            if (!gravacoes.awaitTermination(ESPERA_DE_FECHAMENTO_S, TimeUnit.SECONDS)) {
                log.warn("Gravação do custo não terminou em {} s; seguindo com o desligamento",
                        ESPERA_DE_FECHAMENTO_S);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- retratos públicos

    /**
     * Um ponto da série que o gráfico de custo desenha.
     *
     * @param porOrigem o custo do dia dividido por origem, que é o que a barra empilhada mostra.
     *                  Só as origens com gasto aparecem; dia lido de um arquivo antigo, sem o
     *                  bloco, vem com o mapa vazio e o {@code totalUsd} ainda certo.
     */
    public record DiaDeCusto(LocalDate dia, double totalUsd, long tokensEntrada, long tokensSaida,
            long cacheEscrito, long cacheLido, long buscasWeb, boolean custoIncompleto,
            Map<Origem, Double> porOrigem) {

        public DiaDeCusto {
            porOrigem = porOrigem == null ? Map.of() : Map.copyOf(porOrigem);
        }
    }

    /**
     * O gasto de um intervalo, já dividido.
     *
     * @param percentualDoTeto só faz sentido num relatório de um dia só; vem 0 nos demais, porque
     *                         o teto é diário e comparar uma semana com ele seria enganoso.
     */
    public record Relatorio(LocalDate de, LocalDate ate, double totalUsd, long buscasWeb,
            Map<Origem, Double> porOrigem, Map<String, Double> porModelo,
            List<String> modelosSemPreco, double tetoUsd, int percentualDoTeto) {

        public boolean vazio() {
            return porModelo.isEmpty();
        }

        public int dias() {
            return (int) (ate.toEpochDay() - de.toEpochDay()) + 1;
        }
    }
}
