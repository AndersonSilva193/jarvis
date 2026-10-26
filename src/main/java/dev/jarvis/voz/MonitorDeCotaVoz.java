package dev.jarvis.voz;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;

/**
 * Decide, a cada fala, se o Jarvis usa a voz da ElevenLabs ou a do navegador.
 *
 * A cota real é consultada a cada dez minutos, mas entre uma consulta e outra o Jarvis soma
 * localmente o que gastou — senão um briefing inteiro caberia na janela e a cota estouraria sem
 * aviso. A reserva (VOZ_RESERVA_PCT) existe para a troca acontecer antes do fim do crédito, não
 * depois: quem fala por último é sempre o navegador, nunca o silêncio.
 *
 * <p><b>Todos os avisos daqui saem pelo {@link AvisosProativos}</b>, com as frases do
 * {@link FalasService}. Eles chegam sem ninguém ter pedido, então valem as três regras de lá: nada
 * sai no horário de silêncio (fica na fila e é entregue junto no fim), o mesmo estado não é
 * anunciado duas vezes e o destino é só o dono. Antes este monitor falava direto com o
 * {@code TelegramClient} e tocava o telefone às 3h da manhã.
 *
 * <p>São duas chaves de evento, e a separação importa:
 *
 * <ul>
 *   <li>{@code voz:provedor} — de onde a voz sai agora ({@code navegador:reserva},
 *       {@code navegador:chave}, {@code elevenlabs}). Uma chave para os três porque são estados do
 *       mesmo fato, e é isso que faz a memória do {@code AvisosProativos} reconhecer a
 *       repetição.</li>
 *   <li>{@code voz:cota} — o aviso antecipado. Chave separada porque com a mesma chave ele e o
 *       aviso de provedor apagariam a memória um do outro.</li>
 * </ul>
 */
@Component
public class MonitorDeCotaVoz {

    private static final Logger log = LoggerFactory.getLogger(MonitorDeCotaVoz.class);
    private static final DateTimeFormatter DIA_E_MES = DateTimeFormatter.ofPattern("dd/MM");

    static final String CHAVE_DO_PROVEDOR = "voz:provedor";
    static final String CHAVE_DA_COTA = "voz:cota";

    /** O período de um plano que não informa data de renovação: um só, para sempre. */
    static final String PERIODO_SEM_RENOVACAO = "sem-renovacao";

    /** Por que a voz está no navegador. Null quando ela está na ElevenLabs. */
    public enum Motivo {
        RESERVA("a cota chegou à reserva"),
        COTA("a ElevenLabs recusou por cota"),
        CHAVE("a chave da ElevenLabs foi recusada"),
        LIMITE("a ElevenLabs está limitando as chamadas"),
        DESLIGADO("a voz da ElevenLabs está desligada na configuração");

        private final String explicacao;

        Motivo(String explicacao) {
            this.explicacao = explicacao;
        }

        public String explicacao() {
            return explicacao;
        }
    }

    private final ElevenLabsVoz elevenLabs;
    private final AvisosProativos avisos;
    private final FalasService falas;
    private final DiarioDeVoz diario;
    private final JarvisProperties props;
    private final Clock relogio;

    private volatile SaldoDeVoz saldo = SaldoDeVoz.desconhecido();
    private volatile Motivo motivo;
    private final AtomicLong usadosDesdeAConsulta = new AtomicLong();

    @Autowired
    public MonitorDeCotaVoz(ElevenLabsVoz elevenLabs, AvisosProativos avisos, FalasService falas,
            DiarioDeVoz diario, JarvisProperties props) {
        this(elevenLabs, avisos, falas, diario, props, Clock.systemUTC());
    }

    /** O relógio entra por aqui só nos testes, para a data da renovação ser previsível. */
    MonitorDeCotaVoz(ElevenLabsVoz elevenLabs, AvisosProativos avisos, FalasService falas,
            DiarioDeVoz diario, JarvisProperties props, Clock relogio) {
        this.elevenLabs = elevenLabs;
        this.avisos = avisos;
        this.falas = falas;
        this.diario = diario;
        this.props = props;
        this.relogio = relogio;
        this.motivo = props.vozRemotaConfigurada() ? null : Motivo.DESLIGADO;
    }

    /** Verdadeiro quando a próxima fala pode ir para a ElevenLabs. */
    public boolean remotaAtiva() {
        return motivo == null;
    }

    public Motivo motivo() {
        return motivo;
    }

    public SaldoDeVoz saldo() {
        return saldo;
    }

    /** Caracteres ainda disponíveis, já descontando o que foi gasto desde a última consulta. */
    public long restante() {
        return Math.max(0, saldo.restante() - usadosDesdeAConsulta.get());
    }

    /** A fatia da cota que o Jarvis não gasta. */
    public long reserva() {
        return saldo.limite() * props.reservaDeVozPct() / 100;
    }

    /**
     * Pode sintetizar este texto agora?
     *
     * Além do modo atual, checa o tamanho: um texto maior que o saldo seria recusado pela
     * ElevenLabs depois de já ter custado a viagem, então ele troca o modo antes de tentar.
     */
    public boolean podeSintetizar(int caracteres) {
        if (motivo != null) {
            return false;
        }
        if (!saldo.conhecido()) {
            // Sem saber o saldo (a primeira consulta ainda não voltou), vale tentar: um erro de
            // cota troca o modo na hora.
            return true;
        }
        if (caracteres > restante() || restante() - caracteres < reserva()) {
            trocarParaNavegador(Motivo.RESERVA);
            return false;
        }
        return true;
    }

    /** Soma o que acabou de ser gasto, até a próxima consulta trazer o número oficial. */
    public void registrarUso(int caracteres) {
        usadosDesdeAConsulta.addAndGet(caracteres);
        diario.sintetizou(caracteres);
        if (motivo == null && saldo.conhecido() && restante() < reserva()) {
            trocarParaNavegador(Motivo.RESERVA);
        }
    }

    /** A ElevenLabs recusou: troca o modo e espera a renovação. */
    public void registrarRecusa(CotaDeVozException erro) {
        trocarParaNavegador(switch (erro.motivo()) {
            case "cota" -> Motivo.COTA;
            case "chave" -> Motivo.CHAVE;
            default -> Motivo.LIMITE;
        });
    }

    /**
     * Consulta o saldo oficial na inicialização e a cada dez minutos.
     *
     * É também o caminho de volta: uma consulta que mostre folga acima da reserva traz a voz da
     * ElevenLabs de volta. Depois da data de renovação é exatamente isso que acontece, porque a
     * ElevenLabs zera o contador sozinha.
     */
    @Scheduled(fixedDelay = 600_000, initialDelay = 0)
    public void consultarSaldo() {
        if (!props.vozRemotaConfigurada()) {
            return;
        }
        try {
            SaldoDeVoz novo = elevenLabs.consultarSaldo();
            saldo = novo;
            usadosDesdeAConsulta.set(0);
            log.info("Cota de voz: {} de {} caracteres usados ({}%)",
                    novo.usados(), novo.limite(), novo.percentualUsado());
            avaliar(novo);
            avaliarAvisoAntecipado(novo);
        } catch (CotaDeVozException e) {
            registrarRecusa(e);
        } catch (IOException | RuntimeException e) {
            // Fora do ar: não mexe no modo. Cada fala tenta e, se falhar, desvia só ela.
            log.warn("Não consegui consultar a cota de voz: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Com o saldo novo na mão, decide se fica, se sai ou se volta. */
    private void avaliar(SaldoDeVoz novo) {
        boolean temFolga = novo.conhecido() && novo.restante() > 0 && novo.restante() >= reserva();
        if (motivo == Motivo.DESLIGADO) {
            return;
        }
        if (motivo == null) {
            if (!temFolga) {
                trocarParaNavegador(Motivo.RESERVA);
            }
            return;
        }
        // A consulta só chega aqui tendo dado certo, o que por si só já prova que a chave vale —
        // por isso CHAVE também volta por este caminho.
        if (temFolga) {
            voltarParaElevenLabs();
        }
    }

    // ---------------------------------------------------------------- aviso antecipado

    /**
     * O aviso que chega <b>antes</b> de a voz acabar, uma vez por período de cobrança.
     *
     * <p>Sem ele o primeiro sinal era o aviso da reserva, que é o instante em que a voz da
     * ElevenLabs <i>já</i> foi desligada — notícia dada quando não há mais nada a fazer com ela.
     *
     * <p>Já estando no navegador o aviso <b>não sai, mas o período é marcado</b>: dizer "a cota está
     * em 80%" depois do aviso da reserva é repetir o que a mensagem anterior já disse, e marcar
     * evita reavaliar isso a cada dez minutos pelo resto do período.
     */
    private void avaliarAvisoAntecipado(SaldoDeVoz novo) {
        int limiar = props.avisoDeVozPct();
        if (limiar <= 0 || !novo.conhecido()) {
            return;
        }
        String periodo = periodoDe(novo);
        if (periodo.equals(diario.avisoDoPeriodo())) {
            return;
        }
        if (motivo != null) {
            diario.marcarAvisoDoPeriodo(periodo);
            return;
        }
        if (novo.percentualUsado() < limiar) {
            return;
        }
        // Marca antes de entregar, e marca mesmo que o aviso fique na fila do silêncio: um segundo
        // aviso idêntico esperando as 7h ao lado do primeiro seria pior que nenhum.
        diario.marcarAvisoDoPeriodo(periodo);
        avisos.avisar(CHAVE_DA_COTA, "alta:" + periodo, falaDaCotaAlta(novo), null);
    }

    /**
     * A frase do aviso antecipado, com a projeção quando há ritmo para projetar.
     *
     * <p>Três situações, nesta ordem: sem média, não dá para projetar; projeção que cai na renovação
     * ou depois dela significa que a cota <b>não acaba</b>, e uma data posterior ao instante em que
     * o contador zera não afirmaria nada; o resto é a data projetada.
     */
    private String falaDaCotaAlta(SaldoDeVoz novo) {
        Map<String, String> percentual = Map.of("percentual",
                String.valueOf(novo.percentualUsado()));
        OptionalDouble media = diario.mediaDiaria();
        if (media.isEmpty()) {
            return falas.frase("voz_cota_alta_sem_projecao", percentual);
        }
        // Math.floor de propósito: arredondar para cima prometeria um dia que a cota pode não ter.
        long dias = (long) Math.floor(novo.restante() / media.getAsDouble());
        LocalDate acaba = LocalDate.ofInstant(relogio.instant(), props.zona()).plusDays(dias);
        LocalDate renova = novo.renovaEm() == null ? null
                : LocalDate.ofInstant(novo.renovaEm(), props.zona());
        boolean chegaNaRenovacao = renova != null && !acaba.isBefore(renova);
        return falas.frase(
                chegaNaRenovacao ? "voz_cota_alta_ate_renovacao" : "voz_cota_alta",
                Map.of("percentual", String.valueOf(novo.percentualUsado()),
                        "data", DIA_E_MES.format(chegaNaRenovacao ? renova : acaba)));
    }

    /**
     * O período de cobrança, identificado pela data de renovação.
     *
     * <p>É o único dado de período que existe — a ElevenLabs informa quando o contador zera, não
     * quando ele começou. Plano sem essa data avisa uma vez e não volta a avisar, que é melhor que
     * avisar todo dia.
     */
    private static String periodoDe(SaldoDeVoz saldo) {
        return saldo.renovaEm() == null ? PERIODO_SEM_RENOVACAO : saldo.renovaEm().toString();
    }

    // ---------------------------------------------------------------- troca de provedor

    private void trocarParaNavegador(Motivo novo) {
        if (motivo != null) {
            return;
        }
        motivo = novo;
        diario.trocouDeProvedor();
        log.info("Voz no navegador: {}", novo.explicacao());
        String texto = novo == Motivo.RESERVA
                ? falas.frase("voz_na_reserva", Map.of("renovacao", renovacao()))
                // Chave recusada ou limite de chamadas não têm data para resolver sozinhos, e
                // mandar "até 31/01" nesse caso seria mentira.
                : falas.frase("voz_indisponivel", Map.of("motivo", novo.explicacao()));
        avisos.avisar(CHAVE_DO_PROVEDOR, "navegador:" + novo.name().toLowerCase(Locale.ROOT), texto,
                null);
    }

    private void voltarParaElevenLabs() {
        motivo = null;
        diario.trocouDeProvedor();
        log.info("Voz de volta na ElevenLabs: {} caracteres disponíveis", saldo.restante());
        avisos.avisar(CHAVE_DO_PROVEDOR, "elevenlabs", falas.frase("voz_renovada", Map.of()), null);
    }

    /** Data da renovação em dd/MM, ou "a renovação" quando a API não informa. */
    private String renovacao() {
        Instant renovaEm = saldo.renovaEm();
        if (renovaEm == null || renovaEm.isBefore(relogio.instant())) {
            return "a renovação";
        }
        return DIA_E_MES.format(renovaEm.atZone(props.zona()));
    }
}
