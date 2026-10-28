package dev.jarvis.ia.provedor;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.PcService;

/**
 * Se o modelo do PC pode atender agora e, se não, por quê.
 *
 * <p>Lê duas coisas: o {@link PcService#online()} e o último {@code X-Pc-Ia} que o agente mandou
 * ({@code ativo=1;ollama=1;modelo=...;carregado=1;gpu=1;vramLivreMb=9216;...;ocupada=0;inferindo=0}).
 * Responde antes de qualquer {@code inferir} sair: mandar um pedido que vai falhar custa o prazo
 * inteiro, e com alguém esperando é o prazo inteiro mais o Claude.
 *
 * <p>Os quatro motivos, conferidos nesta ordem:
 * <ul>
 *   <li>{@code pc_offline}: o agente não dá sinal de vida;</li>
 *   <li>{@code ollama_offline}: sem cabeçalho recente (agente antigo, ou sem consulta há mais de
 *       {@link #VALIDADE_DO_CABECALHO}), IA desligada no PC ({@code ativo=0}) ou Ollama fora do ar;</li>
 *   <li>{@code gpu_ocupada}: a GPU está em uso por outra coisa — um jogo, uma renderização;</li>
 *   <li>{@code sem_vram}: o modelo não está carregado e não cabe. Com ele <b>já carregado</b> a VRAM
 *       livre é pequena justamente por causa dele, então ela não reprova; e sem leitura da GPU só
 *       passa o modelo que já está na memória.</li>
 * </ul>
 *
 * <p>O cabeçalho é lido só como número e chave conhecida: nada dele vai ao prompt.
 */
@Component
public class DisponibilidadeLocal {

    public static final String PC_OFFLINE = "pc_offline";
    public static final String OLLAMA_OFFLINE = "ollama_offline";
    public static final String SEM_VRAM = "sem_vram";
    public static final String GPU_OCUPADA = "gpu_ocupada";

    /** O mesmo limite do {@code PcService}: duas voltas de long poll perdidas. */
    static final Duration VALIDADE_DO_CABECALHO = Duration.ofSeconds(90);
    private static final int MAX_PARES = 32;

    /** {@code pode} verdadeiro com motivo vazio, ou falso com um dos quatro códigos. */
    public record Situacao(boolean pode, String motivo) {
        static Situacao livre() {
            return new Situacao(true, "");
        }

        static Situacao nao(String motivo) {
            return new Situacao(false, motivo);
        }
    }

    private final PcService pc;
    private final JarvisProperties props;
    private final Clock relogio;

    @Autowired
    public DisponibilidadeLocal(PcService pc, JarvisProperties props) {
        this(pc, props, Clock.systemUTC());
    }

    public DisponibilidadeLocal(PcService pc, JarvisProperties props, Clock relogio) {
        this.pc = pc;
        this.props = props;
        this.relogio = relogio;
    }

    public Situacao agora() {
        if (!pc.online()) {
            return Situacao.nao(PC_OFFLINE);
        }
        var estado = pc.estadoIa().orElse(null);
        if (estado == null
                || Duration.between(estado.recebidoEm(), relogio.instant()).compareTo(VALIDADE_DO_CABECALHO) > 0) {
            return Situacao.nao(OLLAMA_OFFLINE);
        }
        Map<String, String> ia = ler(estado.cabecalho());
        if (!sim(ia, "ativo") || !sim(ia, "ollama")) {
            return Situacao.nao(OLLAMA_OFFLINE);
        }
        if (sim(ia, "ocupada")) {
            return Situacao.nao(GPU_OCUPADA);
        }
        if (!sim(ia, "carregado")
                && (!sim(ia, "gpu") || numero(ia, "vramLivreMb") < props.vramMinimaDaIaLocal())) {
            return Situacao.nao(SEM_VRAM);
        }
        return Situacao.livre();
    }

    /**
     * O {@code modelo=} do último cabeçalho, para o seletor dizer quem está pensando. Vai ao dono
     * (resposta e HUD), nunca ao prompt, e mesmo assim só passa nome de modelo do Ollama: letras,
     * dígitos e {@code ._:/-}, até 60 caracteres. O resto é vazio.
     */
    public java.util.Optional<String> modeloAnunciado() {
        return pc.estadoIa()
                .map(estado -> ler(estado.cabecalho()).getOrDefault("modelo", ""))
                .filter(modelo -> modelo.matches("[A-Za-z0-9._:/-]{1,60}"));
    }

    /** {@code chave=valor} separados por {@code ;}. Par torto é ignorado, não derruba o resto. */
    static Map<String, String> ler(String cabecalho) {
        Map<String, String> pares = new HashMap<>();
        if (cabecalho == null) return pares;
        for (String par : cabecalho.split(";", MAX_PARES + 1)) {
            int igual = par.indexOf('=');
            if (igual <= 0 || pares.size() >= MAX_PARES) continue;
            pares.put(par.substring(0, igual).trim(), par.substring(igual + 1).trim());
        }
        return pares;
    }

    private static boolean sim(Map<String, String> ia, String chave) {
        return "1".equals(ia.get(chave));
    }

    /** Número ausente ou torto é -1: falta de medida nunca passa por VRAM sobrando. */
    private static long numero(Map<String, String> ia, String chave) {
        try {
            return Long.parseLong(ia.getOrDefault(chave, ""));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
