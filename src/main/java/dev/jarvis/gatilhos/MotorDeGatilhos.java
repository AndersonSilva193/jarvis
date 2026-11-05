package dev.jarvis.gatilhos;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import dev.jarvis.agente.Freio;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.PcDesligou;
import dev.jarvis.pc.PcVoltou;
import dev.jarvis.tarefas.TarefasDeFundo;

/**
 * Quem liga evento a regra. Para cada {@link EventoDeGatilho}, cada regra ativa da mesma fonte que
 * casa com ele vira uma tarefa de fundo — se o freio deixa e se a regra não passou do teto da hora.
 *
 * <p>Três travas, nesta ordem:
 * <ol>
 *   <li><b>O freio</b> ({@code Freio.podeAgirSozinho("gatilho")}): pausa manual ou teto autônomo de
 *       custo, nada dispara. Não entra no diário: nada foi feito.</li>
 *   <li><b>O teto por hora</b> ({@code GATILHO_MAX_POR_HORA}): um site oscilando ou uma pasta
 *       recebendo cem arquivos não viram cem tarefas. Um WARN por regra por hora.</li>
 *   <li><b>A cascata</b>, que mora em quem publica: tarefa nascida de gatilho não publica
 *       {@code TAREFA_FALHOU}.</li>
 * </ol>
 *
 * <p>O despacho roda numa thread própria: quem publica é o contato do PC, o monitor de sites, o fim
 * de uma tarefa — nenhum deles pode esperar a gravação de uma tarefa e de uma linha de diário.
 */
@Component
public class MotorDeGatilhos {

    private static final Logger log = LoggerFactory.getLogger(MotorDeGatilhos.class);
    static final Duration JANELA = Duration.ofHours(1);

    private final Gatilhos gatilhos;
    private final TarefasDeFundo tarefas;
    private final Freio freio;
    private final DiarioDeAcoes diario;
    private final JarvisProperties props;
    private final Clock relogio;
    private final Executor despacho;
    /** Disparos por regra na última hora. Em memória: um restart zera a conta, e tudo bem. */
    private final Map<String, Deque<Instant>> recentes = new HashMap<>();
    private final Set<String> avisadosNoTeto = new HashSet<>();

    @Autowired
    public MotorDeGatilhos(Gatilhos gatilhos, TarefasDeFundo tarefas, Freio freio, DiarioDeAcoes diario,
            JarvisProperties props) {
        this(gatilhos, tarefas, freio, diario, props, Clock.systemUTC(),
                Executors.newSingleThreadExecutor(Thread.ofVirtual().name("gatilhos-", 0).factory()));
    }

    public MotorDeGatilhos(Gatilhos gatilhos, TarefasDeFundo tarefas, Freio freio, DiarioDeAcoes diario,
            JarvisProperties props, Clock relogio, Executor despacho) {
        this.gatilhos = gatilhos;
        this.tarefas = tarefas;
        this.freio = freio;
        this.diario = diario;
        this.props = props;
        this.relogio = relogio;
        this.despacho = despacho;
    }

    @EventListener
    public void aoEvento(EventoDeGatilho evento) {
        despacho.execute(() -> {
            try {
                processar(evento);
            } catch (RuntimeException e) {
                log.warn("Gatilhos: falha ao tratar {}: {}", evento.fonte().chave(), e.getMessage());
            }
        });
    }

    /**
     * Só a volta depois de uma ausência é "o PC ligou". O primeiro contato da partida ({@code
     * ausencia} nula) é a VPS que reiniciou com o PC já ligado.
     */
    @EventListener
    public void aoPcVoltar(PcVoltou voltou) {
        if (voltou.ausencia() == null) return;
        aoEvento(EventoDeGatilho.de(FonteDeGatilho.PC_LIGOU, voltou.em(),
                "ausenciaSegundos", String.valueOf(voltou.ausencia().toSeconds())));
    }

    @EventListener
    public void aoPcDesligar(PcDesligou desligou) {
        aoEvento(EventoDeGatilho.de(FonteDeGatilho.PC_DESLIGOU, desligou.percebidoEm(),
                "ultimoContato", desligou.ultimoContato().toString()));
    }

    /** Síncrono: devolve os ids das tarefas criadas. Público para os testes e para o relógio. */
    public List<String> processar(EventoDeGatilho evento) {
        List<String> criadas = new ArrayList<>();
        for (RegraDeGatilho regra : gatilhos.ativas(evento.fonte())) {
            if (!casa(regra, evento)) continue;
            String id = disparar(regra, evento);
            if (id != null) criadas.add(id);
        }
        return criadas;
    }

    private static boolean casa(RegraDeGatilho regra, EventoDeGatilho evento) {
        // O horário é da regra: o evento já nasce nomeando a regra que venceu.
        if (regra.fonte() == FonteDeGatilho.HORARIO) return regra.id().equals(evento.dado("regra"));
        return regra.filtro().casa(evento);
    }

    private String disparar(RegraDeGatilho regra, EventoDeGatilho evento) {
        if (freio != null && !freio.podeAgirSozinho("gatilho")) {
            log.info("Gatilho {}: {} aconteceu, mas o trabalho autônomo está pausado", regra.id(), evento.fonte().chave());
            return null;
        }
        Instant agora = relogio.instant();
        synchronized (recentes) {
            Deque<Instant> fila = recentes.computeIfAbsent(regra.id(), k -> new ArrayDeque<>());
            while (!fila.isEmpty() && !fila.peekFirst().isAfter(agora.minus(JANELA))) fila.removeFirst();
            if (fila.isEmpty()) avisadosNoTeto.remove(regra.id());
            if (fila.size() >= props.gatilhoMaxPorHora()) {
                if (avisadosNoTeto.add(regra.id()))
                    log.warn("Gatilho {} chegou a {} disparos na última hora; os próximos ficam de fora",
                            regra.id(), props.gatilhoMaxPorHora());
                return null;
            }
            fila.addLast(agora);
        }
        var disparo = tarefas.iniciarPorGatilho(regra.id(), regra.instrucao(), DadoDoEvento.formatar(evento),
                regra.usuario());
        if (!disparo.iniciou()) {
            synchronized (recentes) { recentes.get(regra.id()).removeLastOccurrence(agora); }
            log.warn("Gatilho {} não criou a tarefa: {}", regra.id(), disparo.recusa());
            return null;
        }
        gatilhos.registrarDisparo(regra.id(), agora);
        if (diario != null) {
            diario.registrar(TipoDeAcao.GATILHO_DISPARADO, "Gatilho " + regra.id() + " (" + regra.evento()
                    + "): tarefa " + disparo.id(), OrigemDaAcao.GATILHO, regra.usuario(), null);
        }
        log.info("Gatilho {} disparou com {}: tarefa {}", regra.id(), evento.fonte().chave(), disparo.id());
        return disparo.id();
    }
}
