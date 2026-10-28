package dev.jarvis.ia.provedor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Quem pensa em cada chamada: o modelo do PC ou o Claude, segundo o {@link ModoDeIa} que o dono
 * escolheu.
 *
 * <ul>
 *   <li><b>AUTO</b>: o local se a {@link DisponibilidadeLocal} deixar; senão o Claude, calado.</li>
 *   <li><b>LOCAL</b>: o local; indisponível, o Claude assim mesmo — o dono pediu o local, então
 *       fica sabendo, <b>uma vez por período</b> de indisponibilidade, pelo
 *       {@link AvisosProativos}.</li>
 *   <li><b>CLAUDE</b>: sempre o Claude.</li>
 * </ul>
 *
 * <p>Acima do modo estão a <b>chave mestra</b> ({@code IA_LOCAL_ATIVA}/{@code IA_LOCAL_APOIO}:
 * desligada, tudo vai ao Claude, como antes de existir seletor) e o <b>pedido pontual</b> ("pergunta
 * pro Claude: ..."), que vale só para a thread do turno. Busca na web e computer use nunca passam
 * por aqui: são da Anthropic por construção, e o {@link Origem#COMPUTER_USE} que chegar responde
 * Claude.
 *
 * <p>Nesta etapa só as tarefas de apoio perguntam — é o {@link ProvedorDeApoio}. O <b>efetivo</b>
 * (quem está pensando, e por quê) é o delas, e é ele que o evento {@link ModoDeIaEvento} leva à HUD
 * quando muda: troca de modo, entrada e saída de fallback. Um tique de 30 s reavalia sem ninguém
 * chamar, para a HUD não mostrar "local" de um PC que desligou de madrugada.
 *
 * <p>O modo mora num {@code modo-ia.json} ao lado do {@code operacoes.json}, no volume
 * {@code pc-operacoes} — pasta derivada, a regra do {@code CatalogoDoPc}. O
 * {@code IA_MODO_PADRAO} é só o da primeira partida.
 */
@Component
public class SeletorDeModelo {

    private static final Logger log = LoggerFactory.getLogger(SeletorDeModelo.class);

    /** A chave mestra está desligada: nem o modo LOCAL leva ao PC. */
    public static final String IA_LOCAL_DESLIGADA = "ia_local_desligada";
    /** "Pergunta pro Claude: ...", só naquele pedido. */
    public static final String PONTUAL = "pontual";
    /** Computer use (e busca na web) são da Anthropic, sem escolha. */
    public static final String SO_ANTHROPIC = "so_anthropic";

    static final String ARQUIVO = "modo-ia.json";
    private static final long MAX_BYTES = 4_096;
    /** A chave do aviso no {@code AvisosProativos}; o estado leva o início do período. */
    static final String CHAVE_DO_AVISO = "ia:local";

    /** As origens que este seletor decide nesta etapa. */
    private static final Set<Origem> APOIO = Set.of(Origem.BRIEFING, Origem.INICIALIZACAO,
            Origem.ANOTADOR, Origem.CLASSIFICADOR);

    /** Os motivos que querem dizer "o PC não pode agora", e não "ninguém pediu o PC". */
    private static final Set<String> INDISPONIBILIDADE = Set.of(DisponibilidadeLocal.PC_OFFLINE,
            DisponibilidadeLocal.OLLAMA_OFFLINE, DisponibilidadeLocal.SEM_VRAM,
            DisponibilidadeLocal.GPU_OCUPADA);

    public enum Provedor {
        LOCAL("local"), CLAUDE("claude");

        private final String chave;

        Provedor(String chave) {
            this.chave = chave;
        }

        public String chave() {
            return chave;
        }
    }

    /**
     * A decisão de uma chamada. Motivo vazio quando o modo foi cumprido como pedido (o local no
     * AUTO/LOCAL, o Claude no CLAUDE); senão, o porquê do Claude.
     */
    public record Escolha(Provedor provedor, String motivo) {
        public boolean local() {
            return provedor == Provedor.LOCAL;
        }

        /** Era para ser o local e o PC não pode: é isto que conta reserva no medidor. */
        public boolean indisponivel() {
            return INDISPONIBILIDADE.contains(motivo);
        }
    }

    /** O que a HUD, o evento e o "qual modelo você está usando?" mostram. */
    public record Retrato(ModoDeIa modo, Provedor provedor, String modelo, String motivo) {
        public ModoDeIaEvento evento() {
            return new ModoDeIaEvento(modo.chave(), provedor.chave(), modelo, motivo);
        }
    }

    /** O escopo do pedido pontual. Fecha sem lançar, para caber num try-with-resources simples. */
    public interface Escopo extends AutoCloseable {
        @Override
        void close();
    }

    private final DisponibilidadeLocal disponibilidade;
    private final JarvisProperties props;
    private final JsonMapper json;
    /** Os quatro abaixo são nulos no {@link #emMemoria}: ninguém para ouvir, avisar ou anotar. */
    private final ApplicationEventPublisher eventos;
    private final AvisosProativos avisos;
    private final FalasService falas;
    private final DiarioDeAcoes diario;
    private final Clock relogio;
    /** Nulo no modo em memória. */
    private final Path arquivo;
    private final ModoDeIa padrao;

    private final ThreadLocal<Boolean> pontual = new ThreadLocal<>();

    private final Object trava = new Object();
    private volatile ModoDeIa modo;
    private volatile boolean carregado;
    /** O último efetivo publicado; nulo antes da primeira avaliação. */
    private Escolha efetivo;
    /** Quando o PC deixou de poder atender, ou nulo enquanto ele pode. */
    private Instant inicioDaIndisponibilidade;
    private boolean avisadoNestePeriodo;

    @Autowired
    public SeletorDeModelo(DisponibilidadeLocal disponibilidade, JarvisProperties props,
            JsonMapper json, ApplicationEventPublisher eventos, AvisosProativos avisos,
            FalasService falas, DiarioDeAcoes diario) {
        this(disponibilidade, props, json, eventos, avisos, falas, diario, Clock.systemUTC(),
                caminho(props));
    }

    /** O relógio e o arquivo entram por aqui nos testes. */
    public SeletorDeModelo(DisponibilidadeLocal disponibilidade, JarvisProperties props,
            JsonMapper json, ApplicationEventPublisher eventos, AvisosProativos avisos,
            FalasService falas, DiarioDeAcoes diario, Clock relogio, Path arquivo) {
        this.disponibilidade = disponibilidade;
        this.props = props;
        this.json = json;
        this.eventos = eventos;
        this.avisos = avisos;
        this.falas = falas;
        this.diario = diario;
        this.relogio = relogio;
        this.arquivo = arquivo;
        this.padrao = padrao(props);
        this.modo = padrao;
        this.carregado = arquivo == null;
    }

    /**
     * Seletor sem disco, sem evento, sem aviso e sem diário, sempre no modo padrão. É o de quem monta
     * o {@code ProvedorDeApoio} à mão — e aí ele se comporta exatamente como antes do seletor.
     */
    public static SeletorDeModelo emMemoria(DisponibilidadeLocal disponibilidade,
            JarvisProperties props) {
        return new SeletorDeModelo(disponibilidade, props, null, null, null, null, null,
                Clock.systemUTC(), null);
    }

    private static Path caminho(JarvisProperties props) {
        String operacoes = props == null || props.pc() == null
                || props.pc().operacoesPasta() == null || props.pc().operacoesPasta().isBlank()
                        ? "/data/pc/operacoes.json" : props.pc().operacoesPasta();
        return Path.of(operacoes).resolveSibling(ARQUIVO);
    }

    /** O WARN de valor torto sai aqui, uma vez, na subida — a cada pedido seria ruído. */
    private static ModoDeIa padrao(JarvisProperties props) {
        String bruto = props == null ? "" : props.modoPadraoDaIa();
        if (bruto.isBlank()) {
            return ModoDeIa.AUTO;
        }
        return ModoDeIa.deChave(bruto).orElseGet(() -> {
            log.warn("IA_MODO_PADRAO='{}' não é auto, local nem claude; fico no auto.", bruto);
            return ModoDeIa.AUTO;
        });
    }

    // ---------------------------------------------------------------- escolha

    public ModoDeIa modo() {
        carregarSePreciso();
        return modo;
    }

    /** Quem atende esta chamada. Pode mandar o aviso do LOCAL e publicar o evento de fallback. */
    public Escolha escolher(Origem origem) {
        if (Boolean.TRUE.equals(pontual.get())) {
            return new Escolha(Provedor.CLAUDE, PONTUAL);
        }
        if (origem == Origem.COMPUTER_USE) {
            return new Escolha(Provedor.CLAUDE, SO_ANTHROPIC);
        }
        if (!APOIO.contains(origem)) {
            // A conversa entra no seletor na 9.7; até lá o efetivo publicado é o do apoio, e uma
            // pergunta de outra origem só responde, sem mexer nele.
            return decidir(props.iaLocalAtiva());
        }
        return avaliar(true, false);
    }

    /**
     * Abre o pedido pontual nesta thread: até o {@code close}, toda escolha é o Claude. O turno
     * inteiro roda numa thread só (o lock do {@code Assistente}), então o classificador do turno — e,
     * quando a conversa passar por aqui, o laço de ferramentas — herdam sem fiação nova.
     */
    public Escopo claudeNestePedido() {
        Boolean antes = pontual.get();
        pontual.set(Boolean.TRUE);
        return () -> {
            if (antes == null) {
                pontual.remove();
            } else {
                pontual.set(antes);
            }
        };
    }

    /** O retrato de agora, sem aviso: é uma pergunta, não uma chamada que caiu no Claude. */
    public Retrato retrato() {
        return retratoDe(avaliar(false, false));
    }

    /**
     * Reavalia sem ninguém ter chamado, para a entrada e a saída de fallback chegarem à HUD mesmo
     * com o Jarvis quieto. Não avisa: a frase do aviso só é verdade quando uma chamada caiu no Claude.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 30_000)
    public void reavaliar() {
        avaliar(false, false);
    }

    // ---------------------------------------------------------------- troca

    /**
     * Troca o modo, grava, anota no diário e publica o evento. Mesmo modo não faz nada — nem diário,
     * nem evento —, e é o {@code false} que deixa a resposta dizer "já estou no modo local".
     *
     * @param canal de onde veio a troca ({@code telegram}, {@code hud}), para o diário
     */
    public boolean mudar(ModoDeIa novo, String canal) {
        carregarSePreciso();
        ModoDeIa anterior;
        synchronized (trava) {
            anterior = modo;
            if (anterior == novo) {
                return false;
            }
            modo = novo;
            gravar(novo);
        }
        log.info("Modo de IA: {} -> {} ({})", anterior.chave(), novo.chave(), canal);
        if (diario != null) {
            diario.registrarDoCanal(TipoDeAcao.TROCA_DE_MODELO,
                    "Modo de IA: " + anterior.rotulo() + " → " + novo.rotulo() + ".", canal);
        }
        Escolha agora = avaliar(false, true);
        if (novo == ModoDeIa.LOCAL && agora.indisponivel()) {
            // Quem acabou de pedir o local já ouviu, na própria resposta, que o PC não pode: o
            // aviso deste período está dado.
            synchronized (trava) {
                avisadoNestePeriodo = true;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- miolo

    /** A decisão pura, sem estado: chave mestra, modo e disponibilidade. */
    private Escolha decidir(boolean chaveLigada) {
        ModoDeIa atual = modo();
        if (atual == ModoDeIa.CLAUDE) {
            return new Escolha(Provedor.CLAUDE, "");
        }
        if (!chaveLigada) {
            return new Escolha(Provedor.CLAUDE, IA_LOCAL_DESLIGADA);
        }
        DisponibilidadeLocal.Situacao situacao = disponibilidade.agora();
        return situacao.pode() ? new Escolha(Provedor.LOCAL, "")
                : new Escolha(Provedor.CLAUDE, situacao.motivo());
    }

    /**
     * Decide para o apoio e atualiza o efetivo e o período de indisponibilidade. O aviso e o evento
     * saem fora da trava: o Telegram e a HUD não seguram quem está escolhendo.
     */
    private Escolha avaliar(boolean podeAvisar, boolean forcarEvento) {
        Escolha escolha = decidir(props.apoioLocal());
        boolean mudou;
        String estadoDoAviso = null;
        synchronized (trava) {
            if (escolha.indisponivel()) {
                if (inicioDaIndisponibilidade == null) {
                    inicioDaIndisponibilidade = relogio.instant();
                    avisadoNestePeriodo = false;
                }
                if (podeAvisar && modo == ModoDeIa.LOCAL && !avisadoNestePeriodo) {
                    avisadoNestePeriodo = true;
                    estadoDoAviso = "indisponivel:" + inicioDaIndisponibilidade;
                }
            } else {
                inicioDaIndisponibilidade = null;
                avisadoNestePeriodo = false;
            }
            mudou = !escolha.equals(efetivo);
            efetivo = escolha;
        }
        if (estadoDoAviso != null) {
            avisar(estadoDoAviso);
        }
        if (mudou || forcarEvento) {
            publicar(retratoDe(escolha));
        }
        return escolha;
    }

    /** Falha no aviso nunca derruba a chamada: o Claude já vai responder de qualquer jeito. */
    private void avisar(String estado) {
        if (avisos == null || falas == null) {
            return;
        }
        try {
            avisos.avisar(CHAVE_DO_AVISO, estado,
                    falas.frase("ia_local_indisponivel", java.util.Map.of()), null);
        } catch (RuntimeException e) {
            log.warn("Não consegui avisar que o local está indisponível: {}", e.getMessage());
        }
    }

    private void publicar(Retrato retrato) {
        if (eventos == null) {
            return;
        }
        try {
            eventos.publishEvent(retrato.evento());
        } catch (RuntimeException e) {
            log.warn("Não consegui publicar o modo de IA: {}", e.getMessage());
        }
    }

    private Retrato retratoDe(Escolha escolha) {
        String modelo = escolha.local()
                ? disponibilidade.modeloAnunciado().orElse("modelo local")
                : modeloDoClaude();
        return new Retrato(modo(), escolha.provedor(), modelo, escolha.motivo());
    }

    /** O {@code ANTHROPIC_MODELO}, ou vazio. */
    public String modeloDoClaude() {
        return props.anthropic() == null || props.anthropic().modelo() == null
                ? "" : props.anthropic().modelo();
    }

    /** O motivo como se fala: "o Claude, porque o PC está offline". */
    public static String motivoFalado(String motivo) {
        return switch (motivo == null ? "" : motivo) {
            case DisponibilidadeLocal.PC_OFFLINE -> "o PC está offline";
            case DisponibilidadeLocal.OLLAMA_OFFLINE -> "o Ollama do PC não está respondendo";
            case DisponibilidadeLocal.GPU_OCUPADA -> "a GPU do PC está ocupada";
            case DisponibilidadeLocal.SEM_VRAM -> "falta memória de vídeo no PC";
            case IA_LOCAL_DESLIGADA -> "a IA local está desligada na configuração";
            default -> motivo;
        };
    }

    // ---------------------------------------------------------------- disco

    /** Fora do construtor, pelo motivo do {@code CatalogoDoPc}: I/O de volume atrasa a subida. */
    @EventListener(ApplicationReadyEvent.class)
    public void naPartida() {
        carregarSePreciso();
    }

    private void carregarSePreciso() {
        if (carregado) {
            return;
        }
        synchronized (trava) {
            if (carregado) {
                return;
            }
            carregado = true;
            ler();
        }
    }

    /** Arquivo ausente é primeira partida; ilegível ou com modo torto é WARN e o padrão. */
    private void ler() {
        if (arquivo == null || !Files.isRegularFile(arquivo)) {
            return;
        }
        try {
            JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
            String chave = Json.texto(raiz.path("modo"));
            ModoDeIa lido = ModoDeIa.deChave(chave).orElse(null);
            if (lido == null) {
                log.warn("Modo de IA '{}' em {} não existe; fico no {}.", chave, arquivo,
                        padrao.chave());
                return;
            }
            modo = lido;
            log.info("Modo de IA recuperado: {}", lido.chave());
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui ler o modo de IA em {}: {}", arquivo, e.getMessage());
        }
    }

    /** Disco falhando vira WARN: o modo vale em memória até o restart, e a troca não é recusada. */
    private void gravar(ModoDeIa novo) {
        if (arquivo == null) {
            return;
        }
        ObjectNode raiz = json.createObjectNode();
        raiz.put("modo", novo.chave());
        raiz.put("desde", relogio.instant().toString());
        try {
            ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
        } catch (IOException | RuntimeException e) {
            log.warn("Não consegui gravar o modo de IA em {}: {}", arquivo, e.getMessage());
        }
    }
}
