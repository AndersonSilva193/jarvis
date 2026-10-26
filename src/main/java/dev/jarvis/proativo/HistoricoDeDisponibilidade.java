package dev.jarvis.proativo;

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
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.json.JsonMapper;

/**
 * Quanto cada site ficou no ar, um JSON por dia, 30 dias.
 *
 * <p>Existe para a pergunta que o aviso de queda não responde: "o site caiu" é um instante, e "o
 * site cai toda semana" é um padrão. O primeiro chega no Telegram; o segundo só aparece se alguém
 * contar os dias — e é daqui que sai a série de um gráfico de disponibilidade depois.
 *
 * <p>O desenho é o do {@code DiarioDeAcoes}, de propósito, com as mesmas razões: gravação
 * write-behind numa thread virtual única (o monitor não espera o disco da VPS), virada do dia
 * conferida <b>preguiçosamente</b> no registro <i>e</i> pelo cron de meia-noite (o container pode
 * estar parado às 00:00), expurgo também na partida (um container desligado quarenta dias volta com
 * arquivos vencidos), e registro que <b>nunca lança</b>: perder uma contagem é aceitável, derrubar
 * a checagem por causa dela não.
 *
 * <p>O que muda é a granularidade: aqui o dia é <b>agregado por site</b>. Ver
 * {@link DiaDeDisponibilidade}.
 */
@Component
public class HistoricoDeDisponibilidade {

    private static final Logger log = LoggerFactory.getLogger(HistoricoDeDisponibilidade.class);

    /**
     * Quantos dias ficam no volume.
     *
     * Constante na classe, e não configuração, pela mesma regra do {@code DiarioDeAcoes}: não vale
     * mais um componente num record construído por posição em dezenas de testes para um número que
     * ninguém vai querer mexer.
     */
    static final int DIAS_GUARDADOS = 30;

    private static final long ESPERA_DE_FECHAMENTO_S = 5;

    /** Um dia já somado, pronto para virar ponto de gráfico. */
    public record Dia(LocalDate dia, Map<String, Contagem> porSite) {
    }

    /**
     * As contagens de um site num dia.
     *
     * @param disponibilidade fração do dia no ar, de 0 a 1. Dia sem checagem nenhuma devolve 1 só
     *                        porque não há o que afirmar — e dia sem arquivo nem aparece na série.
     */
    public record Contagem(int checagens, int falhas, int quedas) {
        public double disponibilidade() {
            return checagens <= 0 ? 1 : 1.0 - (double) falhas / checagens;
        }
    }

    private final JarvisProperties props;
    private final Clock relogio;
    private final DisponibilidadeEmArquivo arquivo;
    private final ExecutorService gravacoes;

    /** Protege o dia corrente e os retratos pendentes; a gravação roda fora dele. */
    private final Object trava = new Object();
    private final Map<LocalDate, DiaDeDisponibilidade> pendentes = new LinkedHashMap<>();

    private DiaDeDisponibilidade dia;

    @Autowired
    public HistoricoDeDisponibilidade(JarvisProperties props, JsonMapper json) {
        this(props, json, Clock.systemUTC(), props.pastaDoProativo());
    }

    /** O relógio e a pasta entram por aqui nos testes, para o dia e o disco serem previsíveis. */
    public HistoricoDeDisponibilidade(JarvisProperties props, JsonMapper json, Clock relogio,
            Path pasta) {
        this.props = props;
        this.relogio = relogio;
        this.arquivo = new DisponibilidadeEmArquivo(pasta, json, props.zona());
        this.gravacoes = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("disponibilidade").factory());
    }

    // ---------------------------------------------------------------- registro

    /** Anota uma checagem e volta na hora, sem esperar o disco. Não lança nunca. */
    public void registrarChecagem(String site, boolean ok) {
        comODiaDeHoje(hoje -> hoje.somarChecagem(site, ok));
    }

    /** Anota que o site passou a ser considerado fora do ar. */
    public void registrarQueda(String site) {
        comODiaDeHoje(hoje -> hoje.somarQueda(site));
    }

    private void comODiaDeHoje(java.util.function.Consumer<DiaDeDisponibilidade> trabalho) {
        try {
            synchronized (trava) {
                virarSePrecisar(hojeLocal());
                trabalho.accept(dia);
                pendentes.put(dia.dia(), dia.copia());
            }
            naFila(false, this::gravarPendentes);
        } catch (RuntimeException e) {
            log.warn("Não consegui anotar a disponibilidade: {}", e.getMessage());
        }
    }

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
     * Os últimos {@code dias} dias com movimento, hoje incluído, do mais antigo ao mais novo.
     *
     * <p><b>Dia sem arquivo é dia omitido</b>, não dia com 100%: zero falhas ali afirmaria "estava
     * tudo no ar" onde a verdade é "eu estava desligado". É a mesma regra do
     * {@code MedidorDeCusto.serieDiaria}, e é o que impede um gráfico de inventar um mês perfeito.
     */
    public List<Dia> serieDiaria(int dias) {
        int quantos = Math.clamp(dias, 1, DIAS_GUARDADOS);
        LocalDate hoje = hojeLocal();
        Map<LocalDate, DiaDeDisponibilidade> emMemoria = new LinkedHashMap<>();
        synchronized (trava) {
            pendentes.forEach((data, estado) -> emMemoria.put(data, estado.copia()));
            if (dia != null) {
                emMemoria.put(dia.dia(), dia.copia());
            }
        }
        List<Dia> serie = new ArrayList<>();
        for (LocalDate d = hoje.minusDays(quantos - 1L); !d.isAfter(hoje); d = d.plusDays(1)) {
            DiaDeDisponibilidade retrato = emMemoria.get(d);
            if (retrato == null && arquivo.existe(d)) {
                retrato = arquivo.carregar(d);
            }
            if (retrato != null && !retrato.vazio()) {
                serie.add(paraDia(retrato));
            }
        }
        return serie;
    }

    private static Dia paraDia(DiaDeDisponibilidade estado) {
        Map<String, Contagem> porSite = new LinkedHashMap<>();
        estado.porSite().forEach((site, contagem) -> porSite.put(site,
                new Contagem(contagem.checagens(), contagem.falhas(), contagem.quedas())));
        return new Dia(estado.dia(), Map.copyOf(porSite));
    }

    // ---------------------------------------------------------------- disco e dia

    /** Fecha o dia que acabou e varre o que passou do prazo. */
    @Scheduled(cron = "0 0 0 * * *", zone = "${jarvis.fuso-horario}")
    public void virarODia() {
        DiaDeDisponibilidade saindo;
        synchronized (trava) {
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
        }
        expurgar();
    }

    /** Varre os dias velhos na partida, além da meia-noite. */
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
                log.info("Disponibilidade: {} dia(s) acima de {} dias apagados", apagados,
                        DIAS_GUARDADOS);
            }
        });
    }

    /** Rede de segurança: o retrato que falhou por disco cheio tem outra chance. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void gravarPendente() {
        naFila(false, this::gravarPendentes);
    }

    private void gravarPendentes() {
        List<DiaDeDisponibilidade> retratos;
        synchronized (trava) {
            retratos = new ArrayList<>(pendentes.values());
        }
        for (DiaDeDisponibilidade retrato : retratos) {
            try {
                arquivo.salvar(retrato);
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui gravar a disponibilidade de {}: {}", retrato.dia(),
                        e.getMessage());
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
            log.warn("Histórico de disponibilidade já fechado; retrato descartado");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.warn("Falha ao gravar o histórico de disponibilidade: {}", e.getMessage());
        }
    }

    /** Costura de teste: espera a fila de gravação esvaziar. */
    public void aguardar() {
        naFila(true, () -> { });
    }

    @PreDestroy
    public void fechar() {
        naFila(true, this::gravarPendentes);
        gravacoes.shutdown();
    }
}
