package dev.jarvis.aprovacao;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.autonomia.Concessao;
import dev.jarvis.autonomia.Concessoes;
import dev.jarvis.autonomia.Nivel;
import dev.jarvis.autonomia.PoliticaDeAutonomia;
import dev.jarvis.conversa.Confirmacao;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;

/**
 * A aprovação humana de uso único, consumida antes de qualquer chamada ao modelo.
 *
 * <p>Este é o <b>único</b> caminho de confirmação do Jarvis: toda ferramenta que vá mexer no PC do
 * usuário — rodar rotina, substituir arquivo, desfazer edição, desligar a máquina — pede aqui e
 * devolve ao modelo apenas a {@code pergunta}, para ele repetir com o próprio tom e esperar a
 * resposta; não há palavra, código nem frase exata a cobrar. Quem consome a aprovação é o
 * {@code Assistente.responder}, <b>antes</b> de falar com o Claude, e é isso que torna impossível o
 * modelo se autoconfirmar: ele nunca vê a decisão, só o resultado dela.
 *
 * <p>As garantias, todas no {@link #tratar}:
 *
 * <ol>
 *   <li><b>uma pendência por vez</b> — é um PC e um usuário; duas tornariam ambíguo a qual delas um
 *       "confirmo" responde;</li>
 *   <li><b>mensagem nova</b> — {@code pedidoAtual} diferente do {@code pedidoOrigem} que criou a
 *       pendência, então confirmar dentro do mesmo turno é recusado;</li>
 *   <li><b>mesmo usuário autenticado</b>, e nunca usuário zero;</li>
 *   <li><b>uso único</b> — a pendência é retirada antes de o {@code Supplier} rodar;</li>
 *   <li><b>amarrada à ação e aos argumentos exatos</b> — eles estão capturados dentro do
 *       {@code Supplier} na hora do pedido, não refeitos pelo modelo na hora da execução;</li>
 *   <li><b>prazo por risco</b> — {@link Risco#COMUM} vale 10 minutos, {@link Risco#CRITICO} 2.</li>
 * </ol>
 *
 * <p>A {@code pergunta} é o que vai ser mostrado ao usuário, montado em Java a partir de dado real
 * (nome da rotina, nome do arquivo, objetivo), no formato <i>o que vai acontecer + o impacto numa
 * frase + "Posso?"</i>. Nunca texto escrito pelo modelo: é a pergunta que o usuário responde, e
 * deixá-la ao modelo seria deixá-lo escolher o que o "sim" significa.
 *
 * <p>Componente próprio, e não um campo das ferramentas, para o {@code Clock} entrar pelo construtor
 * e o teste de expiração ser relógio fixo em vez de minutos de {@code sleep}.
 *
 * <p><b>Se pergunta ou não, quem decide é a {@link PoliticaDeAutonomia}.</b> As ferramentas entram
 * por {@link #encaminhar}, com a chave da ação e o alvo, e é ali que a política escolhe entre rodar
 * já ({@link Nivel#FAZ_E_AVISA}), abrir a pendência ({@link Nivel#PERGUNTA}) ou recusar
 * ({@link Nivel#PROIBIDO}). Os dois caminhos que executam passam pelo mesmo {@link #rodar}, então o
 * diário continua sendo anotado num lugar só — agora dizendo se houve pergunta.
 *
 * <p><b>A permissão aprendida nasce aqui</b>, porque é aqui que o "sim" acontece. Cada pendência
 * aberta pelo {@link #encaminhar} carrega a ação e o alvo; o {@link #rodar} conta o "sim" nas
 * {@link Concessoes} e, na terceira vez seguida sem "não", acrescenta à resposta a oferta de parar de
 * perguntar — que é uma pendência {@link #TIPO_CONCEDER_PERMANENTE} como outra qualquer, decidida
 * pelo {@code ClassificadorDeResposta}. O "não" chega pelo {@link #recusarPendente}.
 */
@Component
public class Aprovacoes {

    private static final Logger log = LoggerFactory.getLogger(Aprovacoes.class);

    /**
     * O tipo das pendências de desligar e reiniciar.
     *
     * Separado do {@code "pc"} genérico só para o diário de ações poder dizer "desliguei o PC" em
     * vez de "apaguei um arquivo" — distinguir os dois pela {@code descricao} seria farejar texto.
     * Para o {@link #tratar}, nada muda: a frase esperada é derivada de "não é rotina", então este
     * tipo continua caindo em {@code "confirmo pc <código>"}.
     */
    public static final String TIPO_PC_ENERGIA = "pc_energia";

    /**
     * O tipo da pendência de apagar <b>muitos</b> arquivos de uma vez.
     *
     * <p>Apagar um arquivo não pergunta mais — vai para a lixeira e volta pelo
     * {@code restaurar_arquivo}. O que pergunta é o pedido que apaga em massa, e ele é <b>uma</b>
     * pendência para a lista inteira. De propósito este tipo <b>não</b> está no
     * {@link #tipoNoDiario}: o {@code Supplier} anota uma linha por arquivo, cada uma com o
     * {@code idLixeira} que a desfaz, e mapeá-lo aqui somaria uma linha agregada em cima dessas.
     * Para o {@link #tratar} nada muda: a frase esperada é derivada de "não é rotina", então
     * {@code pc_lixeira} também cai em {@code confirmo pc <código>}.
     */
    public static final String TIPO_PC_LIXEIRA = "pc_lixeira";

    /**
     * A oferta "quer que eu pare de perguntar?". O "sim" grava uma concessão permanente; o "não"
     * cala a oferta por 30 dias. Fora do {@link #tipoNoDiario}: quem anota é a própria
     * {@link Concessoes}, como {@code concessao_criada}.
     */
    public static final String TIPO_CONCEDER_PERMANENTE = "conceder_permanente";

    /** O quanto a aprovação espera pelo usuário. Quanto mais grave a ação, menos tempo em aberto. */
    public enum Risco {
        COMUM(Duration.ofMinutes(10)),
        CRITICO(Duration.ofMinutes(2));

        private final Duration validade;

        Risco(Duration validade) { this.validade = validade; }

        public Duration validade() { return validade; }
    }

    public enum Cancelamento { CANCELADA, INEXISTENTE, RECUSADA }
    public record ResultadoAprovado(String texto, dev.jarvis.diario.Desfazer desfazer, boolean executada) { }

    /**
     * O que aconteceu com uma ação encaminhada: o {@code nivel} que a política escolheu, o texto que
     * volta ao modelo e, só em {@link Nivel#PERGUNTA}, a confirmação em aberto.
     */
    public record Encaminhamento(Nivel nivel, String texto, Confirmacao confirmacao) { }

    /**
     * @param acao      a ação do catálogo, só nas pendências abertas pelo {@link #encaminhar}; é
     *                  o que conta o "sim" e o "não" para a permissão aprendida
     * @param aoRecusar o que roda num "não" explícito, além de contar a recusa; pode ser nulo
     */
    private record Pendente(String tipo, String descricao, String pergunta, Risco risco,
            String codigo, long usuario, String pedidoOrigem, Instant expira,
            Supplier<ResultadoAprovado> executar, AcaoAutonoma acao, String alvo,
            Runnable aoRecusar) {

        Pendente(String tipo, String descricao, String pergunta, Risco risco, String codigo,
                long usuario, String pedidoOrigem, Instant expira, Supplier<ResultadoAprovado> executar) {
            this(tipo, descricao, pergunta, risco, codigo, usuario, pedidoOrigem, expira, executar,
                    null, null, null);
        }

        Pendente daAcao(AcaoAutonoma acao, String alvo) {
            return new Pendente(tipo, descricao, pergunta, risco, codigo, usuario, pedidoOrigem,
                    expira, executar, acao, alvo == null || alvo.isEmpty() ? null : alvo, aoRecusar);
        }
    }

    private final SecureRandom sorteio = new SecureRandom();
    private final Clock relogio;
    /** Pode ser nulo: os construtores antigos existem para os testes que não olham o diário. */
    private final DiarioDeAcoes diario;
    /** Nunca nula: os construtores antigos ficam só com os padrões, que é o comportamento de antes. */
    private final PoliticaDeAutonomia politica;
    private Pendente pendente;
    private final java.util.ArrayDeque<AutorizacaoDaTarefa> filaDeTarefas = new java.util.ArrayDeque<>();
    private final java.util.Map<String, AutorizacaoDaTarefa> adiadas = new java.util.HashMap<>();

    /** A decisão é consumida na conversa; a ação só pode ser executada pelo trabalhador. */
    public final class AutorizacaoDaTarefa {
        private enum Estado { NA_FILA, PERGUNTANDO, APROVADA, RECUSADA, EXPIRADA, CANCELADA, EXECUTADA }
        private final String idTarefa, pedido, tipo, descricao, pergunta, canal;
        private final long usuario;
        private final Risco risco;
        private final AcaoAutonoma acao;
        private final String alvo;
        private final Supplier<ResultadoAprovado> executar;
        private final java.util.function.Consumer<String> avisar;
        private volatile Estado estado = Estado.NA_FILA;
        private volatile Confirmacao confirmacao;
        private Pendente original;

        private AutorizacaoDaTarefa(String idTarefa, long usuario, String tipo, String descricao,
                String pergunta, Risco risco, String canal, AcaoAutonoma acao, String alvo,
                Supplier<ResultadoAprovado> executar, java.util.function.Consumer<String> avisar) {
            this.idTarefa = idTarefa; this.usuario = usuario; this.tipo = tipo;
            this.descricao = descricao; this.pergunta = pergunta; this.risco = risco; this.canal = canal;
            this.acao = acao; this.alvo = alvo; this.executar = executar; this.avisar = avisar;
            pedido = "tarefa:" + idTarefa + ":" + java.util.UUID.randomUUID();
        }
        public Confirmacao confirmacao() { return confirmacao; }
        public boolean resolvida() { return estado != Estado.NA_FILA && estado != Estado.PERGUNTANDO; }
        public boolean aprovada() { return estado == Estado.APROVADA; }
        public String executar() {
            synchronized (Aprovacoes.this) {
                if (estado != Estado.APROVADA) throw new dev.jarvis.tarefas.ControleDaTarefa(
                        "Aprovação " + estado.name().toLowerCase() + ".");
                estado = Estado.EXECUTADA;
                adiadas.remove(pedido);
            }
            if (acao != null && politica.nivel(acao, alvo) == Nivel.PROIBIDO)
                throw new dev.jarvis.tarefas.ControleDaTarefa("A configuração passou a proibir " + acao.oQue(alvo));
            return rodar(original, canal, true, null);
        }
    }

    public AutorizacaoDaTarefa adiarParaTarefa(String idTarefa, long usuario, String tipo,
            String descricao, String pergunta, Risco risco, String canal,
            AcaoAutonoma acao, String alvo, Supplier<ResultadoAprovado> executar,
            java.util.function.Consumer<String> avisar) {
        if (usuario <= 0 || idTarefa == null || idTarefa.isBlank())
            throw new IllegalArgumentException("tarefa autenticada necessária");
        var autorizacao = new AutorizacaoDaTarefa(idTarefa, usuario, tipo, descricao, pergunta,
                risco, canal, acao, alvo, executar, avisar);
        synchronized (this) {
            filaDeTarefas.add(autorizacao); adiadas.put(autorizacao.pedido, autorizacao);
        }
        return autorizacao;
    }

    /** O prazo nasce junto da pergunta, nunca enquanto ela aguarda outra aprovação. */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 250)
    public void processarFilaDeTarefas() {
        AutorizacaoDaTarefa mostrar = null;
        synchronized (this) {
            Instant agora = relogio.instant();
            for (var a : adiadas.values()) {
                if (a.estado != AutorizacaoDaTarefa.Estado.PERGUNTANDO) continue;
                if (!agora.isBefore(a.original.expira())) {
                    a.estado = AutorizacaoDaTarefa.Estado.EXPIRADA;
                    if (pendente != null && pendente.pedidoOrigem().equals(a.pedido)) pendente = null;
                } else if (pendente == null || !pendente.pedidoOrigem().equals(a.pedido))
                    a.estado = AutorizacaoDaTarefa.Estado.CANCELADA;
            }
            if (pendente != null && !agora.isBefore(pendente.expira())) pendente = null;
            if (pendente != null) return;
            while (!filaDeTarefas.isEmpty()) {
                var a = filaDeTarefas.removeFirst();
                if (a.estado != AutorizacaoDaTarefa.Estado.NA_FILA) continue;
                String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
                Instant expira = agora.plus(a.risco.validade());
                a.original = new Pendente(a.tipo, a.descricao, a.pergunta, a.risco, codigo,
                        a.usuario, a.pedido, expira, a.executar, a.acao, a.alvo, null);
                pendente = new Pendente(a.tipo, a.descricao, a.pergunta, a.risco, codigo,
                        a.usuario, a.pedido, expira, () -> {
                            synchronized (Aprovacoes.this) {
                                if (a.estado != AutorizacaoDaTarefa.Estado.PERGUNTANDO)
                                    return new ResultadoAprovado("A tarefa já foi cancelada.", null, false);
                                a.estado = AutorizacaoDaTarefa.Estado.APROVADA;
                            }
                            return new ResultadoAprovado("Autorizado. A tarefa vai continuar.", null, false);
                        }, null, null, () -> a.estado = AutorizacaoDaTarefa.Estado.RECUSADA);
                a.confirmacao = new Confirmacao(codigo, a.descricao, expira.toString(), a.pergunta);
                a.estado = AutorizacaoDaTarefa.Estado.PERGUNTANDO;
                mostrar = a;
                break;
            }
        }
        if (mostrar != null) {
            try { mostrar.avisar.accept(mostrar.pergunta); }
            catch (RuntimeException e) { log.warn("Não consegui apresentar a pergunta da tarefa", e); }
        }
    }

    public synchronized void cancelarDaTarefa(String idTarefa) {
        for (var a : adiadas.values()) if (a.idTarefa.equals(idTarefa)) {
            a.estado = AutorizacaoDaTarefa.Estado.CANCELADA;
            if (pendente != null && pendente.pedidoOrigem().equals(a.pedido)) pendente = null;
        }
        filaDeTarefas.removeIf(a -> a.idTarefa.equals(idTarefa));
        adiadas.values().removeIf(a -> a.idTarefa.equals(idTarefa));
    }

    public Aprovacoes() { this(Clock.systemUTC(), null); }
    public Aprovacoes(Clock relogio) { this(relogio, null); }
    public Aprovacoes(DiarioDeAcoes diario) { this(Clock.systemUTC(), diario); }

    public Aprovacoes(Clock relogio, DiarioDeAcoes diario) {
        this(relogio, diario, PoliticaDeAutonomia.soPadroes());
    }

    @Autowired
    public Aprovacoes(DiarioDeAcoes diario, PoliticaDeAutonomia politica) {
        this(Clock.systemUTC(), diario, politica);
    }

    public Aprovacoes(Clock relogio, DiarioDeAcoes diario, PoliticaDeAutonomia politica) {
        this.relogio = relogio;
        this.diario = diario;
        this.politica = politica == null ? PoliticaDeAutonomia.soPadroes() : politica;
    }

    /** O nível que a política dá a esta ação agora — para quem precisa saber antes de encaminhar. */
    public Nivel nivel(AcaoAutonoma acao, String alvo) {
        return politica.nivel(acao, alvo);
    }

    /** A ação cujo resultado é só uma frase, no formato do {@link #encaminhar}: o {@code pedir} faz o mesmo. */
    public static Supplier<ResultadoAprovado> frase(Supplier<String> executar) {
        return () -> new ResultadoAprovado(executar.get(), null, true);
    }

    /**
     * A porta de toda ação do catálogo. A política decide; esta classe só cumpre:
     *
     * <ul>
     *   <li>{@link Nivel#PROIBIDO} — nada roda, e volta uma recusa curta;</li>
     *   <li>{@link Nivel#PERGUNTA} — abre a pendência, exatamente como o {@link #pedirComResultado};</li>
     *   <li>{@link Nivel#FAZ_E_AVISA} — roda agora, pelo mesmo {@link #rodar} da aprovação, e o
     *       diário anota a linha como feita sem pergunta.</li>
     * </ul>
     *
     * <p>Os argumentos são os mesmos do {@code pedir}, montados em Java pela ferramenta, mais a
     * chave, o alvo e o canal. Pedido sem usuário autenticado é recusado nos três níveis, com a
     * mesma exceção do {@code pedir}.
     */
    public Encaminhamento encaminhar(AcaoAutonoma acao, String alvo, String tipo, String descricao,
            String pergunta, Risco risco, long usuario, String pedidoOrigem, String canal,
            Supplier<ResultadoAprovado> executar) {
        if (usuario <= 0 || pedidoOrigem == null || pedidoOrigem.isBlank())
            throw new IllegalArgumentException("pedido autenticado necessario");
        PoliticaDeAutonomia.Decisao decisao = politica.decidir(acao, alvo);
        Nivel nivel = decisao.nivel();
        var tarefa = dev.jarvis.tarefas.ContextoDaTarefa.atual();
        if (tarefa != null && nivel != Nivel.PROIBIDO) {
            var concessoes = politica.concessoes();
            var recibo = concessoes == null ? java.util.Optional.<Concessao>empty()
                    : concessoes.vigenteNaTarefa(acao, alvo, tarefa.id());
            if (recibo.isPresent()) {
                String texto = rodar(new Pendente(tipo, descricao, pergunta, risco, "", usuario,
                        pedidoOrigem, relogio.instant(), executar), canal, true, null);
                concessoes.marcarUso(recibo.get().id(), usuario, canal);
                return new Encaminhamento(Nivel.FAZ_E_AVISA, texto, null);
            }
            if (nivel == Nivel.PERGUNTA) {
                var autorizacao = adiarParaTarefa(tarefa.id(), usuario, tipo, descricao, pergunta,
                        risco, canal, acao, alvo, executar, tarefa::avisar);
                throw new dev.jarvis.tarefas.SuspensaoDaTarefa(
                        dev.jarvis.tarefas.SuspensaoDaTarefa.Tipo.APROVACAO, "", null,
                        autorizacao::resolvida,
                        () -> dev.jarvis.ferramentas.ResultadoFerramenta.texto(autorizacao.executar()));
            }
        }
        return switch (nivel) {
            case PROIBIDO -> new Encaminhamento(nivel, "Não posso " + acao.oQue(alvo)
                    + ": a minha configuração de autonomia proíbe. Nada foi feito.", null);
            case PERGUNTA -> {
                Confirmacao confirmacao;
                synchronized (this) {
                    confirmacao = pedirComResultado(tipo, descricao, pergunta, risco, usuario,
                            pedidoOrigem, executar);
                    pendente = pendente.daAcao(acao, alvo);
                }
                yield new Encaminhamento(nivel, confirmacao.pergunta(), confirmacao);
            }
            case FAZ_E_AVISA -> {
                Pendente agora = new Pendente(tipo, descricao, pergunta, risco, "", usuario,
                        pedidoOrigem, relogio.instant(), executar);
                String texto = rodar(agora, canal, false, null);
                Concessao liberou = decisao.concessao();
                if (liberou != null && politica.concessoes() != null) {
                    politica.concessoes().marcarUso(liberou.id(), usuario, canal);
                }
                yield new Encaminhamento(nivel, texto, null);
            }
        };
    }

    public synchronized Confirmacao pedir(String tipo, String descricao, String pergunta,
            Risco risco, long usuario, String pedidoOrigem, Supplier<String> executar) {
        return pedir(tipo, descricao, pergunta, risco, usuario, pedidoOrigem,
                relogio.instant().plus(risco.validade()), executar);
    }

    /**
     * O {@code prazoMaximo} só abaixa o prazo, nunca o estica: é por ele que a aprovação de uma
     * edição não sobrevive à proposta que o agente preparou no PC.
     */
    public synchronized Confirmacao pedir(String tipo, String descricao, String pergunta,
            Risco risco, long usuario, String pedidoOrigem, Instant prazoMaximo,
            Supplier<String> executar) {
        if (usuario <= 0 || pedidoOrigem == null || pedidoOrigem.isBlank())
            throw new IllegalArgumentException("pedido autenticado necessario");
        if (pendente != null && relogio.instant().isBefore(pendente.expira())
                && (adiadas.containsKey(pendente.pedidoOrigem()) || pendente.usuario() != usuario
                        || pendente.pedidoOrigem().equals(pedidoOrigem)))
            throw new IllegalStateException("ja existe uma aprovacao em aberto");
        String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
        Instant expira = relogio.instant().plus(risco.validade());
        if (prazoMaximo.isBefore(expira)) expira = prazoMaximo;
        if (!relogio.instant().isBefore(expira))
            throw new IllegalStateException("a proposta expirou; prepare uma nova edição");
        String aMostrar = pergunta == null || pergunta.isBlank() ? descricao : pergunta;
        pendente = new Pendente(tipo, descricao, aMostrar, risco, codigo, usuario, pedidoOrigem,
                expira, () -> new ResultadoAprovado(executar.get(), null, true));
        return new Confirmacao(codigo, descricao, expira.toString(), aMostrar);
    }

    /** A identidade da tarefa vem da execução, sem extrair IDs da frase devolvida ao usuário. */
    public synchronized Confirmacao pedirComResultado(String tipo, String descricao, String pergunta,
            Risco risco, long usuario, String pedidoOrigem, Supplier<ResultadoAprovado> executar) {
        Confirmacao confirmacao = pedir(tipo, descricao, pergunta, risco, usuario, pedidoOrigem, () -> "");
        Pendente p = pendente;
        pendente = new Pendente(p.tipo(), p.descricao(), p.pergunta(), p.risco(), p.codigo(),
                p.usuario(), p.pedidoOrigem(), p.expira(), executar);
        return confirmacao;
    }

    /** Cancela somente a aprovacao de tarefa visual, sem tocar em tarefas ja iniciadas. */
    public synchronized Cancelamento cancelarTarefaPendente(long usuario, String pedidoAtual) {
        Pendente p = pendente;
        if (p == null || !p.tipo().equals("computer")) return Cancelamento.INEXISTENTE;
        if (!relogio.instant().isBefore(p.expira())) {
            pendente = null;
            return Cancelamento.INEXISTENTE;
        }
        if (usuario <= 0 || usuario != p.usuario() || pedidoAtual == null
                || pedidoAtual.equals(p.pedidoOrigem())) return Cancelamento.RECUSADA;
        pendente = null;
        return Cancelamento.CANCELADA;
    }

    /** Vazio = mensagem comum; presente = resposta deterministica sem passar pelo modelo. */
    public Optional<String> tratar(String texto, long usuario, String pedidoAtual) {
        return tratar(texto, usuario, pedidoAtual, "");
    }

    /** Como o {@link #tratar(String, long, String)}, dizendo de que canal veio a confirmação. */
    public Optional<String> tratar(String texto, long usuario, String pedidoAtual, String canal) {
        String recebido = normalizar(texto);
        boolean fraseDeConfirmacao = switch (recebido) {
            case "confirmo", "confirmo a tarefa", "confirmo a operacao", "pode prosseguir",
                    "pode proceguir", "pode continuar", "pode seguir", "pode executar",
                    "sim pode prosseguir" -> true;
            default -> false;
        };
        if (!fraseDeConfirmacao && !recebido.matches(
                "confirmo(?: pc| rotina)? [0-9]{6}|cancelar|cancelo [0-9]{6}"))
            return Optional.empty();
        Pendente executar = null;
        Pendente recusada = null;
        synchronized (this) {
            Pendente p = pendente;
            if (p == null) return Optional.of("Não há aprovação pendente. Nenhuma ação foi executada; "
                    + "se uma edição falhou, releia o arquivo e prepare uma nova proposta.");
            if (usuario <= 0 || usuario != p.usuario() || pedidoAtual == null
                    || pedidoAtual.equals(p.pedidoOrigem()))
                return Optional.of("Aprovação recusada: envie uma nova mensagem autenticada.");
            if (!relogio.instant().isBefore(p.expira())) {
                pendente = null;
                return Optional.of("A aprovação expirou; faça um novo pedido.");
            }
            if (recebido.equals("cancelar") || recebido.equals("cancelo " + p.codigo())) {
                pendente = null;
                recusada = p;
            } else {
                String esperado = p.tipo().equals("rotina") ? "confirmo rotina " + p.codigo()
                        : "confirmo pc " + p.codigo();
                if (!fraseDeConfirmacao && !recebido.equals(esperado)
                        && !recebido.equals("confirmo " + p.codigo()))
                    return Optional.of("Código incorreto; a operação continua pendente até expirar ou ser cancelada.");
                pendente = null;
                executar = p;
            }
        }
        if (recusada != null) {
            aoRecusar(recusada);
            return Optional.of("Operação cancelada.");
        }
        return Optional.of(rodar(executar, canal, true, pedidoAtual));
    }

    /**
     * A pergunta em aberto para este usuário nesta mensagem, ou vazio.
     *
     * <p>É o que permite responder à aprovação em linguagem natural sem gastar nada quando não há
     * nada pendente: só com a pergunta na mão é que o {@link ClassificadorDeResposta} é chamado.
     * As condições são as mesmas do {@link #tratar} — usuário autenticado e igual, mensagem nova,
     * prazo de pé —, e a pendência vencida é descartada aqui mesmo.
     */
    public synchronized Optional<String> pendenciaDe(long usuario, String pedidoAtual) {
        Pendente p = pendente;
        if (p == null) return Optional.empty();
        if (!relogio.instant().isBefore(p.expira())) {
            pendente = null;
            return Optional.empty();
        }
        if (usuario <= 0 || usuario != p.usuario() || pedidoAtual == null
                || pedidoAtual.equals(p.pedidoOrigem())) return Optional.empty();
        return Optional.of(p.pergunta());
    }

    /**
     * Retira a pendência e roda a ação aprovada. Mesmas garantias do {@link #tratar}: uso único, e
     * o {@code Supplier} — com a ação e os argumentos capturados na hora do pedido — rodando fora
     * do monitor, porque ele fala com o PC e pode demorar.
     */
    public Optional<String> executarPendente(long usuario, String pedidoAtual) {
        return executarPendente(usuario, pedidoAtual, "");
    }

    /** Como o {@link #executarPendente(long, String)}, dizendo de que canal veio o "sim". */
    public Optional<String> executarPendente(long usuario, String pedidoAtual, String canal) {
        Pendente executar;
        synchronized (this) {
            if (pendenciaDe(usuario, pedidoAtual).isEmpty()) return Optional.empty();
            executar = pendente;
            pendente = null;
        }
        return Optional.of(rodar(executar, canal, true, pedidoAtual));
    }

    /**
     * O único lugar onde uma ação deste componente roda — aprovada ou liberada pela política —, e por
     * isso o único lugar onde ela é anotada no diário. O {@code perguntada} vai para a linha: é ele
     * que separa "rodei a rotina depois do seu sim" de "rodei a rotina sozinho".
     *
     * <p>Central de propósito: aprovação nova entra no diário sem ninguém precisar lembrar. O que
     * vai para o registro é o {@code tipo} e a {@code descricao} <b>capturados na hora do pedido</b>
     * e montados em Java — nada aqui é refeito pelo modelo.
     *
     * <p>O registro vem <b>depois</b> da execução e nunca a derruba: a ação já aconteceu no PC ou
     * no disco, e o diário é a testemunha dela, não o guarda. Falhar aqui perderia a linha do
     * diário; lançar perderia a resposta de uma ação que já foi feita.
     */
    private String rodar(Pendente p, String canal, boolean perguntada, String pedidoAtual) {
        ResultadoAprovado resultado;
        try { resultado = p.executar().get(); }
        catch (dev.jarvis.tarefas.SuspensaoDaTarefa espera) {
            // A ação já foi despachada: a continuação espera seu resultado, sem despachar outra.
            throw new dev.jarvis.tarefas.SuspensaoDaTarefa(espera.tipo(), espera.alvo(), espera.ate(),
                    espera::pronta, () -> {
                        var retomado = new Pendente(p.tipo(), p.descricao(), p.pergunta(), p.risco(),
                                p.codigo(), p.usuario(), p.pedidoOrigem(), p.expira(), () -> {
                                    try { return new ResultadoAprovado(espera.continuar().texto(), null, true); }
                                    catch (RuntimeException e) { throw e; }
                                    catch (Exception e) { throw new IllegalStateException(e); }
                                }, p.acao(), p.alvo(), p.aoRecusar());
                        return dev.jarvis.ferramentas.ResultadoFerramenta.texto(rodar(retomado, canal, perguntada, pedidoAtual));
                    });
        }
        String resposta = resultado.texto();
        if (perguntada && p.acao() != null && resultado.executada()) {
            resposta += oferecer(p, canal, pedidoAtual);
        }
        TipoDeAcao tipo = tipoNoDiario(p.tipo());
        if (diario != null && tipo != null && resultado.executada()) {
            try {
                diario.registrar(tipo, p.descricao(), OrigemDaAcao.porCanal(canal), p.usuario(),
                        resultado.desfazer(), perguntada);
            } catch (RuntimeException e) {
                log.warn("Ação '{}' executada, mas não entrou no diário: {}",
                        p.tipo(), e.getMessage());
            }
        }
        return resposta;
    }

    /**
     * Conta o "sim" e, se é a terceira vez seguida, abre a oferta de parar de perguntar e devolve a
     * frase a acrescentar; senão, vazio. Nunca derruba a ação, que já aconteceu.
     *
     * <p>Não oferece quando o {@code autonomia.md} tem linha para a ação (a concessão não teria
     * efeito) nem quando outra pendência ocupou o lugar. Quem decide piso, bloqueio e contagem são as
     * {@link Concessoes}: chave CRITICO nunca chega à oferta.
     */
    private String oferecer(Pendente p, String canal, String pedidoAtual) {
        Concessoes concessoes = politica.concessoes();
        if (concessoes == null || pedidoAtual == null || pedidoAtual.isBlank()) return "";
        try {
            AcaoAutonoma acao = p.acao();
            String alvo = p.alvo();
            if (!concessoes.registrarAprovacao(acao, alvo) || politica.arquivoDecide(acao, alvo))
                return "";
            String oQue = acao.oQue(alvo);
            Instant agora = relogio.instant();
            Pendente oferta = new Pendente(TIPO_CONCEDER_PERMANENTE,
                    "Parar de perguntar antes de " + oQue,
                    "Quer que eu pare de perguntar antes de " + oQue + "?", Risco.COMUM,
                    String.format("%06d", sorteio.nextInt(1_000_000)), p.usuario(), pedidoAtual,
                    agora.plus(Risco.COMUM.validade()),
                    () -> concederPermanente(concessoes, acao, alvo, p.usuario(), canal),
                    null, null, () -> concessoes.recusarOferta(acao, alvo));
            synchronized (this) {
                if (pendente != null && agora.isBefore(pendente.expira())) return "";
                pendente = oferta;
            }
            concessoes.registrarOferta(acao, alvo);
            return "\n\nÉ a terceira vez que você aprova " + oQue + ". Quer que eu pare de perguntar?";
        } catch (RuntimeException e) {
            log.warn("Não consegui avaliar a permissão aprendida: {}", e.getMessage());
            return "";
        }
    }

    private static ResultadoAprovado concederPermanente(Concessoes concessoes, AcaoAutonoma acao,
            String alvo, long usuario, String canal) {
        try {
            Concessao c = concessoes.conceder(acao, alvo, Concessao.Escopo.PERMANENTE, null, null,
                    Concessao.Origem.APRENDIDA,
                    Concessoes.APROVACOES_PARA_OFERECER + " aprovações seguidas e um sim à oferta",
                    usuario, canal);
            return new ResultadoAprovado("Combinado: não pergunto mais antes de " + c.oQue()
                    + ". Diga 'volta a perguntar' para cancelar.", null, true);
        } catch (java.io.IOException | IllegalArgumentException e) {
            return new ResultadoAprovado("Não consegui gravar a permissão; continuo perguntando.",
                    null, false);
        }
    }

    /** O "não" explícito: roda o {@code aoRecusar} e zera a sequência daquela ação. */
    private void aoRecusar(Pendente p) {
        try {
            if (p.aoRecusar() != null) p.aoRecusar().run();
            Concessoes concessoes = politica.concessoes();
            if (concessoes != null && p.acao() != null) concessoes.registrarRecusa(p.acao(), p.alvo());
        } catch (RuntimeException e) {
            log.warn("Não consegui registrar a recusa: {}", e.getMessage());
        }
    }

    /**
     * Tipo desconhecido devolve null e só não é anotado: pendência nova não pode quebrar o "sim".
     *
     * <p>O {@link #TIPO_PC_LIXEIRA} está fora daqui <b>de propósito</b>, e não por esquecimento:
     * ele anota uma linha por arquivo de dentro do próprio {@code Supplier}.
     */
    private static TipoDeAcao tipoNoDiario(String tipo) {
        return switch (tipo) {
            case "rotina" -> TipoDeAcao.ROTINA;
            case "computer" -> TipoDeAcao.COMPUTER_USE;
            case "pc" -> TipoDeAcao.APAGAR_ARQUIVO;
            case TIPO_PC_ENERGIA -> TipoDeAcao.DESLIGAR_PC;
            case "memoria" -> TipoDeAcao.ESQUECER_MEMORIA;
            case "tarefa_planilha" -> TipoDeAcao.TAREFA_PLANILHA;
            default -> null;
        };
    }

    /**
     * O "não" à pendência: cancela como o {@link #cancelarPendente} e conta a recusa — zera a
     * sequência da permissão aprendida, e numa oferta a cala por 30 dias. O ajuste ("pode, mas só
     * a pasta src") não é recusa e continua pelo {@link #cancelarPendente}.
     */
    public Cancelamento recusarPendente(long usuario, String pedidoAtual) {
        Pendente recusada;
        synchronized (this) {
            if (pendente == null) return Cancelamento.INEXISTENTE;
            if (pendenciaDe(usuario, pedidoAtual).isEmpty())
                return pendente == null ? Cancelamento.INEXISTENTE : Cancelamento.RECUSADA;
            recusada = pendente;
            pendente = null;
        }
        aoRecusar(recusada);
        return Cancelamento.CANCELADA;
    }

    /** Como o {@link #cancelarTarefaPendente}, mas para qualquer tipo de pendência. */
    public synchronized Cancelamento cancelarPendente(long usuario, String pedidoAtual) {
        if (pendente == null) return Cancelamento.INEXISTENTE;
        if (pendenciaDe(usuario, pedidoAtual).isEmpty())
            return pendente == null ? Cancelamento.INEXISTENTE : Cancelamento.RECUSADA;
        pendente = null;
        return Cancelamento.CANCELADA;
    }

    private static String normalizar(String texto) {
        return ClassificadorDeResposta.normalizar(texto);
    }
}
