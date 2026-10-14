package dev.jarvis.voz;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.telegram.TelegramClient;

/**
 * Decide, a cada fala, se o Jarvis usa a voz da ElevenLabs ou a do navegador.
 *
 * A cota real é consultada a cada dez minutos, mas entre uma consulta e outra o Jarvis soma
 * localmente o que gastou — senão um briefing inteiro caberia na janela e a cota estouraria sem
 * aviso. A reserva (VOZ_RESERVA_PCT) existe para a troca acontecer antes do fim do crédito, não
 * depois: quem fala por último é sempre o navegador, nunca o silêncio.
 */
@Component
public class MonitorDeCotaVoz {

    private static final Logger log = LoggerFactory.getLogger(MonitorDeCotaVoz.class);
    private static final DateTimeFormatter DIA_E_MES = DateTimeFormatter.ofPattern("dd/MM");

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
    private final TelegramClient telegram;
    private final DiarioDeVoz diario;
    private final JarvisProperties props;
    private final Clock relogio;

    private volatile SaldoDeVoz saldo = SaldoDeVoz.desconhecido();
    private volatile Motivo motivo;
    private final AtomicLong usadosDesdeAConsulta = new AtomicLong();

    @Autowired
    public MonitorDeCotaVoz(ElevenLabsVoz elevenLabs, TelegramClient telegram, DiarioDeVoz diario,
            JarvisProperties props) {
        this(elevenLabs, telegram, diario, props, Clock.systemUTC());
    }

    /** O relógio entra por aqui só nos testes, para a data da renovação ser previsível. */
    MonitorDeCotaVoz(ElevenLabsVoz elevenLabs, TelegramClient telegram, DiarioDeVoz diario,
            JarvisProperties props, Clock relogio) {
        this.elevenLabs = elevenLabs;
        this.telegram = telegram;
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

    private void trocarParaNavegador(Motivo novo) {
        if (motivo != null) {
            return;
        }
        motivo = novo;
        diario.trocouDeProvedor();
        log.info("Voz no navegador: {}", novo.explicacao());
        avisar(novo == Motivo.RESERVA
                ? "Senhor, a cota de voz chegou à reserva. Volto à voz do navegador até "
                        + renovacao() + "."
                // Chave recusada ou limite de chamadas não têm data para resolver sozinhos, e
                // mandar "até 31/01" nesse caso seria mentira.
                : "Senhor, " + novo.explicacao() + ". Volto à voz do navegador.");
    }

    private void voltarParaElevenLabs() {
        motivo = null;
        diario.trocouDeProvedor();
        log.info("Voz de volta na ElevenLabs: {} caracteres disponíveis", saldo.restante());
        avisar("Senhor, a cota de voz foi renovada. Volto à minha voz.");
    }

    /** Data da renovação em dd/MM, ou "a renovação" quando a API não informa. */
    private String renovacao() {
        Instant renovaEm = saldo.renovaEm();
        if (renovaEm == null || renovaEm.isBefore(relogio.instant())) {
            return "a renovação";
        }
        return DIA_E_MES.format(renovaEm.atZone(props.zona()));
    }

    /**
     * Um aviso por troca, nunca um por ciclo de dez minutos: quem muda o estado é sempre
     * trocarParaNavegador/voltarParaElevenLabs, e os dois só agem quando o modo mudou de verdade.
     */
    private void avisar(String texto) {
        long chat = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
        if (chat == 0) {
            return;
        }
        telegram.enviarMensagem(chat, texto);
    }
}
