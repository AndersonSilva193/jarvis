package dev.jarvis.agente;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.Desfazer;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.pc.PcService;
import dev.jarvis.tarefas.TarefaDeFundo;
import dev.jarvis.tarefas.TarefaFalada;
import dev.jarvis.tarefas.TarefasDeFundo;

/**
 * O que está acontecendo agora, num texto curto para o bloco <b>dinâmico</b> do system: PC, programa
 * em primeiro plano, freio, pergunta em aberto, tarefas de fundo, as últimas ações e o próximo
 * lembrete. É o que deixa "lê aquele arquivo de novo", "continua de onde parou" e "como está
 * aquilo?" funcionarem sem o dono explicar nem o modelo gastar uma ferramenta para descobrir.
 *
 * <p>Quem pede é o {@code SistemaDoJarvis}, <b>uma vez por pedido</b>, junto com os blocos — o texto
 * tem "há quanto tempo", e remontá-lo no meio do turno mudaria o system entre passos. E só em pedido
 * autenticado de conversa privada: o retrato fala de arquivos, tarefas e perguntas do dono, e em
 * grupo a resposta seria pública.
 *
 * <p>Três regras seguram o texto:
 * <ul>
 *   <li><b>Teto de {@link #MAX_CARACTERES}, cortado por linha.</b> Um corte no meio da linha partiria
 *       um UUID, e um ID pela metade é pior que nenhum: o modelo o mandaria para uma ferramenta que o
 *       recusa. A ordem das linhas é a prioridade — o que fica de fora é sempre o do fim — e o texto
 *       cortado termina em {@link #OMITIDOS}.</li>
 *   <li><b>Fonte que falha some.</b> Cada parte roda no próprio {@code try}, como as seções do
 *       briefing: um diário ilegível não pode derrubar o pedido inteiro.</li>
 *   <li><b>Texto de fora é higienizado de novo.</b> Objetivo, pergunta, descrição e lembrete perdem
 *       caractere de controle, os sinais {@code <<<}/{@code >>>} e os colchetes (que aqui marcam ID). O
 *       {@code X-Pc-Foco} é conferido contra a mesma régua do agente e, fora dela, descartado inteiro
 *       — nunca filtrado até caber, que inventaria um programa.</li>
 * </ul>
 */
@Component
public class RetratoDoMomento {

    private static final Logger log = LoggerFactory.getLogger(RetratoDoMomento.class);

    /** O teto do texto inteiro, marcador incluído. */
    public static final int MAX_CARACTERES = 600;
    /** A última linha de um retrato que não coube. */
    public static final String OMITIDOS = "(mais itens omitidos)";
    /** A régua do agente para o nome do processo ({@code ProgramaEmFoco.valido}). */
    static final Pattern NOME_DE_PROGRAMA = Pattern.compile("[A-Za-z0-9._-]{1,40}");
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F-]{36}");
    /** O foco mais velho que isto já não é "agora": é o mesmo limite do PC online. */
    private static final Duration VALIDADE_DO_FOCO = Duration.ofSeconds(90);
    private static final int MAX_TAREFAS = 3, MAX_ACOES = 3;
    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DIA_E_HORA = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final PcService pc;
    private final Function<Long, List<TarefaDeFundo.Retrato>> tarefas;
    private final Aprovacoes aprovacoes;
    private final DiarioDeAcoes diario;
    private final Supplier<List<LembreteService.Lembrete>> lembretes;
    private final Freio freio;
    private final ZoneId zona;
    private final Clock relogio;

    /**
     * As tarefas vêm por {@code ObjectProvider}: a {@code TarefasDeFundo} monta um
     * {@code CicloDeFerramentas}, que precisa do {@code SistemaDoJarvis}, que precisa deste retrato.
     * Resolvido na hora do pedido, o ciclo não existe.
     */
    @Autowired
    public RetratoDoMomento(PcService pc, ObjectProvider<TarefasDeFundo> tarefas, Aprovacoes aprovacoes,
            DiarioDeAcoes diario, LembreteService lembretes, Freio freio, JarvisProperties props) {
        this(pc, usuario -> {
            TarefasDeFundo t = tarefas == null ? null : tarefas.getIfAvailable();
            return t == null ? List.of() : t.listar(usuario);
        }, aprovacoes, diario, lembretes == null ? null : lembretes::listar, freio, props.zona(),
                Clock.systemUTC());
    }

    /** Toda fonte pode ser nula: a parte dela simplesmente não sai. */
    public RetratoDoMomento(PcService pc, Function<Long, List<TarefaDeFundo.Retrato>> tarefas,
            Aprovacoes aprovacoes, DiarioDeAcoes diario,
            Supplier<List<LembreteService.Lembrete>> lembretes, Freio freio, ZoneId zona, Clock relogio) {
        this.pc = pc;
        this.tarefas = tarefas;
        this.aprovacoes = aprovacoes;
        this.diario = diario;
        this.lembretes = lembretes;
        this.freio = freio;
        this.zona = zona == null ? ZoneId.systemDefault() : zona;
        this.relogio = relogio == null ? Clock.systemUTC() : relogio;
    }

    /**
     * O retrato deste pedido, ou vazio — sem usuário autenticado, ou sem nada a dizer.
     *
     * @param usuario  o usuário autenticado; 0 (grupo, briefing, tarefa) devolve vazio
     * @param idPedido o pedido corrente, que a {@link Aprovacoes#pendenciaDe} exige para não mostrar
     *                 como "em aberto" a pergunta nascida neste mesmo pedido
     */
    public String montar(long usuario, String idPedido) {
        if (usuario <= 0) return "";
        Instant agora = relogio.instant();
        List<String> linhas = new ArrayList<>();
        parte("pc", linhas, () -> linhaDoPc(agora));
        parte("freio", linhas, this::linhaDoFreio);
        parte("pendência", linhas, () -> linhaDaPendencia(usuario, idPedido));
        partes("tarefas", linhas, () -> linhasDasTarefas(usuario, agora));
        partes("diário", linhas, () -> linhasDasAcoes(usuario));
        parte("lembrete", linhas, () -> linhaDoLembrete(agora));
        return cortar(linhas);
    }

    // ---------------------------------------------------------------- as partes

    private String linhaDoPc(Instant agora) {
        if (pc == null) return "";
        if (!pc.online()) {
            return pc.desdeOUltimoContato()
                    .map(d -> "PC: offline há " + TarefaFalada.duracao(agora.minus(d), agora) + ".")
                    .orElse("PC: offline (sem contato desde que o servidor subiu).");
        }
        StringBuilder linha = new StringBuilder("PC: online");
        pc.onlineDesde().ifPresent(desde -> linha.append(" há ").append(TarefaFalada.duracao(desde, agora)));
        String foco = focoValido(agora);
        if (!foco.isEmpty()) linha.append("; em primeiro plano: ").append(foco);
        return linha.append('.').toString();
    }

    /** O {@code X-Pc-Foco} recente e dentro da régua do agente, ou vazio. */
    private String focoValido(Instant agora) {
        return pc.foco()
                .filter(f -> f.recebidoEm() != null
                        && Duration.between(f.recebidoEm(), agora).compareTo(VALIDADE_DO_FOCO) < 0)
                .map(PcService.Foco::nome)
                .filter(nome -> NOME_DE_PROGRAMA.matcher(nome).matches())
                .orElse("");
    }

    private String linhaDoFreio() {
        if (freio == null) return "";
        Freio.Retrato estado = freio.retrato();
        if (estado.estado() == Freio.Estado.PAUSADO) {
            return "Freio puxado: trabalho autônomo pausado desde "
                    + HORA.format(estado.desde().atZone(zona)) + " (o dono libera com 'pode voltar').";
        }
        return freio.tetoAtingido() ? "Freio puxado: teto de custo autônomo de hoje atingido." : "";
    }

    private String linhaDaPendencia(long usuario, String idPedido) {
        if (aprovacoes == null) return "";
        return aprovacoes.pendenciaDe(usuario, idPedido == null ? "" : idPedido)
                .map(p -> "Pergunta em aberto, esperando o dono: \"" + limpar(p, 120) + "\"")
                .orElse("");
    }

    private List<String> linhasDasTarefas(long usuario, Instant agora) {
        if (tarefas == null) return List.of();
        List<TarefaDeFundo.Retrato> ativas = tarefas.apply(usuario).stream()
                .filter(TarefaFalada::ativa).toList();
        List<String> linhas = new ArrayList<>();
        for (var t : ativas.subList(0, Math.min(MAX_TAREFAS, ativas.size()))) {
            String passo = t.passos() > 0 ? ", passo " + t.passos() : "";
            linhas.add("Tarefa de fundo: " + limpar(t.objetivo(), 60) + " — "
                    + limpar(TarefaFalada.status(t, agora, zona), 70) + passo + " [tarefa " + t.id() + "]");
        }
        if (ativas.size() > MAX_TAREFAS) linhas.add("e mais " + (ativas.size() - MAX_TAREFAS) + " tarefa(s) de fundo.");
        return linhas;
    }

    /** As últimas ações, a mais recente primeiro: hoje, e ontem só para completar. */
    private List<String> linhasDasAcoes(long usuario) {
        if (diario == null) return List.of();
        List<AcaoRegistrada> recentes = new ArrayList<>(ultimas(diario.deHoje().acoes(), usuario));
        if (recentes.size() < MAX_ACOES) recentes.addAll(ultimas(diario.deOntem().acoes(), usuario));
        List<String> linhas = new ArrayList<>();
        for (AcaoRegistrada a : recentes.subList(0, Math.min(MAX_ACOES, recentes.size()))) {
            StringBuilder linha = new StringBuilder("Feito ").append(HORA.format(a.instante().atZone(zona)))
                    .append(": ").append(limpar(a.descricao(), 70));
            String arquivo = idDoArquivo(a);
            if (!arquivo.isEmpty()) linha.append(" [arquivo ").append(arquivo).append(']');
            if (a.desfazer() instanceof Desfazer.Lixeira l && UUID.matcher(l.idLixeira()).matches())
                linha.append(" [lixeira ").append(l.idLixeira()).append(']');
            if (a.reversivel() && "disponivel".equals(a.estadoDesfazer()))
                linha.append(" [ação ").append(a.id()).append(']');
            linhas.add(linha.toString());
        }
        return linhas;
    }

    private static List<AcaoRegistrada> ultimas(List<AcaoRegistrada> dia, long usuario) {
        List<AcaoRegistrada> doUsuario = new ArrayList<>();
        for (int i = dia.size() - 1; i >= 0 && doUsuario.size() < MAX_ACOES; i--) {
            AcaoRegistrada a = dia.get(i);
            if (a.usuario() == usuario || a.usuario() == 0) doUsuario.add(a);
        }
        return doUsuario;
    }

    /**
     * O ID do catálogo do arquivo mexido, quando a linha o tem: o {@code Desfazer.Arquivo} de criar,
     * restaurar e copiar, ou a {@code referencia} que a edição do original e o desfazer guardam. É o
     * {@code id} que a {@code ler_arquivo} aceita. Apagar não tem: a entrada saiu do catálogo, e o
     * que volta é o {@code idLixeira}.
     */
    static String idDoArquivo(AcaoRegistrada a) {
        String id = "";
        if (a.desfazer() instanceof Desfazer.Arquivo d) id = d.id();
        else if (a.tipo() == TipoDeAcao.EDICAO_ARQUIVO || a.tipo() == TipoDeAcao.DESFAZER_EDICAO) id = a.referencia();
        return id != null && UUID.matcher(id).matches() ? id : "";
    }

    private String linhaDoLembrete(Instant agora) {
        if (lembretes == null) return "";
        return lembretes.get().stream()
                .filter(l -> l.quando() != null && l.quando().toInstant().isAfter(agora))
                .findFirst()
                .map(l -> "Próximo lembrete: " + DIA_E_HORA.format(l.quando().withZoneSameInstant(zona))
                        + " — " + limpar(l.texto(), 60) + ".")
                .orElse("");
    }

    // ---------------------------------------------------------------- montagem

    private static void parte(String nome, List<String> linhas, Supplier<String> fonte) {
        partes(nome, linhas, () -> {
            String linha = fonte.get();
            return linha == null || linha.isBlank() ? List.of() : List.of(linha);
        });
    }

    private static void partes(String nome, List<String> linhas, Supplier<List<String>> fonte) {
        try {
            for (String linha : fonte.get()) if (linha != null && !linha.isBlank()) linhas.add(linha.strip());
        } catch (RuntimeException e) {
            log.warn("Retrato do momento sem a parte {}: {}", nome, e.getClass().getSimpleName());
        }
    }

    /**
     * Junta as linhas sem passar do teto. Linha que não cabe fica de fora, e com ela todas as
     * seguintes — a ordem é a prioridade, e pular para uma menor bagunçaria isso.
     */
    static String cortar(List<String> linhas) {
        StringBuilder texto = new StringBuilder();
        for (int i = 0; i < linhas.size(); i++) {
            String linha = linhas.get(i);
            boolean ultima = i == linhas.size() - 1;
            int depois = texto.length() + linha.length() + 1;
            if (ultima ? depois > MAX_CARACTERES : depois + OMITIDOS.length() + 1 > MAX_CARACTERES) {
                texto.append(OMITIDOS).append('\n');
                break;
            }
            texto.append(linha).append('\n');
        }
        return texto.toString();
    }

    /** Texto de fora numa linha só, sem o que poderia fingir ser marca nossa, e cortado com "…". */
    static String limpar(String bruto, int max) {
        if (bruto == null) return "";
        String limpo = bruto.replaceAll("\\p{Cntrl}", " ").replace("<<<", "").replace(">>>", "")
                .replace('[', '(').replace(']', ')').replaceAll("\\s+", " ").strip();
        return limpo.length() <= max ? limpo : limpo.substring(0, max - 1).strip() + "…";
    }
}
