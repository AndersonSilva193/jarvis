package dev.jarvis.holograma;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Guarda o último retrato do holograma que a HUD mandou, e o que o servidor mandou à HUD depois dele.
 *
 * <p>O segundo existe porque o retrato chega com atraso: num "abre o holograma e carrega o motor V8
 * explodido" as três ordens saem no mesmo turno, antes de a HUD reportar qualquer coisa. Sem isto, o
 * {@code explodir} acharia o modo fechado e o "mais" partiria do fator de antes. Vale o mais novo dos
 * dois — o que a HUD disse, ou o que o servidor mandou fazer.
 */
@Service
public class EstadoDoHologramaService {

    /** A HUD reposta a cada mudança e a cada cinco minutos; passado disso o retrato é velho. */
    static final Duration VALIDADE = Duration.ofMinutes(10);
    /** O teto da linha no system: ela entra em todo pedido com o modo aberto. */
    static final int MAX_LINHA = 420;
    static final int MAX_GRUPOS_NA_LINHA = 12;
    private static final int MAX_NOME = 60;

    private final Clock relogio;
    private volatile EstadoDoHolograma ultimo;
    /** O fator que o servidor mandou por último, e quando. */
    private volatile Double explosaoEnviada;
    private volatile Instant explosaoEnviadaEm = Instant.EPOCH;
    /** Abrir/carregar ({@code true}) ou fechar ({@code false}) mandado por último, e quando. */
    private volatile Boolean aberturaEnviada;
    private volatile Instant aberturaEnviadaEm = Instant.EPOCH;

    @Autowired
    public EstadoDoHologramaService() {
        this(Clock.systemUTC());
    }

    public EstadoDoHologramaService(Clock relogio) {
        this.relogio = relogio;
    }

    public void guardar(EstadoDoHolograma estado) {
        this.ultimo = estado;
    }

    /** O último retrato, se ainda estiver fresco. */
    public Optional<EstadoDoHolograma> ultimo() {
        EstadoDoHolograma guardado = ultimo;
        return guardado != null && guardado.fresco(relogio.instant(), VALIDADE)
                ? Optional.of(guardado) : Optional.empty();
    }

    /** O retrato, só se o modo estiver aberto: é o único caso em que ele diz algo ao modelo. */
    public Optional<EstadoDoHolograma> aberto() {
        return ultimo().filter(EstadoDoHolograma::aberto);
    }

    /** Aberto agora, contando a ordem de abrir (ou carregar) que a HUD ainda não reportou. */
    public boolean abertoAgora() {
        Optional<EstadoDoHolograma> retrato = ultimo();
        Boolean enviada = aberturaEnviada;
        if (enviada != null && (retrato.isEmpty() || aberturaEnviadaEm.isAfter(retrato.get().instante()))) {
            return enviada;
        }
        return retrato.map(EstadoDoHolograma::aberto).orElse(false);
    }

    /** O fator de explosão atual: o que a HUD reportou, ou o que o servidor mandou depois. */
    public double explosaoAtual() {
        Optional<EstadoDoHolograma> retrato = aberto();
        Double enviada = explosaoEnviada;
        if (enviada != null && (retrato.isEmpty() || explosaoEnviadaEm.isAfter(retrato.get().instante()))) {
            return enviada;
        }
        return retrato.map(EstadoDoHolograma::explosao).orElse(0.0);
    }

    /** Anota o que acabou de ir à HUD, para a próxima ordem do mesmo turno partir dali. */
    public void anotarEnvio(ComandoDoHolograma comando) {
        Instant agora = relogio.instant();
        switch (comando.acao()) {
            case ComandoDoHolograma.ABRIR -> abertura(true, agora);
            case ComandoDoHolograma.CARREGAR -> {
                abertura(true, agora);
                explosao(0.0, agora);
            }
            case ComandoDoHolograma.FECHAR -> abertura(false, agora);
            case ComandoDoHolograma.RESETAR -> explosao(0.0, agora);
            case ComandoDoHolograma.EXPLODIR -> {
                if (comando.dados().get("fator") instanceof Number fator) explosao(fator.doubleValue(), agora);
            }
            default -> { }
        }
    }

    private void abertura(boolean aberto, Instant agora) {
        aberturaEnviada = aberto;
        aberturaEnviadaEm = agora;
    }

    private void explosao(double fator, Instant agora) {
        explosaoEnviada = fator;
        explosaoEnviadaEm = agora;
    }

    /** A linha do bloco dinâmico, ou vazio com o modo fechado. */
    public String linha() {
        return aberto().map(EstadoDoHologramaService::linha).orElse("");
    }

    /**
     * {@code Holograma aberto: 'Motor V8' (86 peças, pronto). Grupos: bloco, pistoes (8)…}
     *
     * <p>Estática e pura para o teste conferir o formato. Os grupos levam a contagem quando passam de
     * uma peça; acima de {@value #MAX_GRUPOS_NA_LINHA}, "e mais N". A linha toda tem teto.
     */
    public static String linha(EstadoDoHolograma estado) {
        if (estado == null || !estado.aberto()) return "";
        StringBuilder linha = new StringBuilder("Holograma aberto");
        if (estado.nome().isBlank() && estado.pecas().isEmpty()) {
            linha.append(estado.gerando() ? ", construindo o primeiro objeto." : ", sem objeto.");
        } else {
            linha.append(": '").append(cortar(estado.nome().isBlank() ? "sem nome" : estado.nome())).append("' (")
                    .append(estado.pecas().size()).append(estado.pecas().size() == 1 ? " peça, " : " peças, ")
                    .append(estado.gerando() ? "construindo" : "pronto").append(").");
            Map<String, Integer> grupos = estado.grupos();
            if (!grupos.isEmpty()) {
                List<String> partes = new ArrayList<>();
                for (var grupo : grupos.entrySet()) {
                    if (partes.size() == MAX_GRUPOS_NA_LINHA) break;
                    partes.add(grupo.getValue() > 1 ? grupo.getKey() + " (" + grupo.getValue() + ")" : grupo.getKey());
                }
                linha.append(" Grupos: ").append(String.join(", ", partes));
                if (grupos.size() > MAX_GRUPOS_NA_LINHA) {
                    linha.append(" e mais ").append(grupos.size() - MAX_GRUPOS_NA_LINHA);
                }
                linha.append('.');
            }
        }
        linha.append(" Explosão ").append(String.format(Locale.ROOT, "%.2f", estado.explosao())
                .replaceAll("0+$", "").replaceAll("\\.$", "")).append('.');
        estado.peca(estado.selecionada()).ifPresent(p ->
                linha.append(" Selecionada: '").append(cortar(p.nome())).append("' (").append(p.grupo()).append(")."));
        if (!estado.isolado().isEmpty()) linha.append(" Isolado: ").append(String.join(", ", estado.isolado())).append('.');
        if (!estado.escondidos().isEmpty()) linha.append(" Escondidos: ").append(String.join(", ", estado.escondidos())).append('.');
        if (!estado.vista().isBlank() && !"livre".equals(estado.vista())) linha.append(" Vista: ").append(estado.vista()).append('.');
        String pronta = linha.toString();
        if (pronta.length() > MAX_LINHA) pronta = pronta.substring(0, MAX_LINHA - 1).strip() + "…";
        return pronta + "\nPara mexer nele use a ferramenta holograma; \"disso\" e \"essa peça\" são a selecionada.";
    }

    private static String cortar(String texto) {
        String limpo = texto.strip().replace('\'', '"').replaceAll("\\s+", " ");
        return limpo.length() <= MAX_NOME ? limpo : limpo.substring(0, MAX_NOME - 1).strip() + "…";
    }
}
