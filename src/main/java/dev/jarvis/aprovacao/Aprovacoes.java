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

    /** O quanto a aprovação espera pelo usuário. Quanto mais grave a ação, menos tempo em aberto. */
    public enum Risco {
        COMUM(Duration.ofMinutes(10)),
        CRITICO(Duration.ofMinutes(2));

        private final Duration validade;

        Risco(Duration validade) { this.validade = validade; }

        public Duration validade() { return validade; }
    }

    public enum Cancelamento { CANCELADA, INEXISTENTE, RECUSADA }

    private record Pendente(String tipo, String descricao, String pergunta, Risco risco,
            String codigo, long usuario, String pedidoOrigem, Instant expira,
            Supplier<String> executar) { }

    private final SecureRandom sorteio = new SecureRandom();
    private final Clock relogio;
    /** Pode ser nulo: os construtores antigos existem para os testes que não olham o diário. */
    private final DiarioDeAcoes diario;
    private Pendente pendente;

    public Aprovacoes() { this(Clock.systemUTC(), null); }
    public Aprovacoes(Clock relogio) { this(relogio, null); }

    @Autowired
    public Aprovacoes(DiarioDeAcoes diario) { this(Clock.systemUTC(), diario); }

    public Aprovacoes(Clock relogio, DiarioDeAcoes diario) {
        this.relogio = relogio;
        this.diario = diario;
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
                && (pendente.usuario() != usuario || pendente.pedidoOrigem().equals(pedidoOrigem)))
            throw new IllegalStateException("ja existe uma aprovacao em aberto");
        String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
        Instant expira = relogio.instant().plus(risco.validade());
        if (prazoMaximo.isBefore(expira)) expira = prazoMaximo;
        if (!relogio.instant().isBefore(expira))
            throw new IllegalStateException("a proposta expirou; prepare uma nova edição");
        String aMostrar = pergunta == null || pergunta.isBlank() ? descricao : pergunta;
        pendente = new Pendente(tipo, descricao, aMostrar, risco, codigo, usuario, pedidoOrigem,
                expira, executar);
        return new Confirmacao(codigo, descricao, expira.toString(), aMostrar);
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
                return Optional.of("Operação cancelada.");
            }
            String esperado = p.tipo().equals("rotina") ? "confirmo rotina " + p.codigo()
                    : "confirmo pc " + p.codigo();
            if (!fraseDeConfirmacao && !recebido.equals(esperado)
                    && !recebido.equals("confirmo " + p.codigo()))
                return Optional.of("Código incorreto; a operação continua pendente até expirar ou ser cancelada.");
            pendente = null;
            executar = p;
        }
        return Optional.of(rodar(executar, canal));
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
        return Optional.of(rodar(executar, canal));
    }

    /**
     * O único lugar onde uma ação aprovada roda — e, por isso, o único lugar onde ela é anotada
     * no diário.
     *
     * <p>Central de propósito: aprovação nova entra no diário sem ninguém precisar lembrar. O que
     * vai para o registro é o {@code tipo} e a {@code descricao} <b>capturados na hora do pedido</b>
     * e montados em Java — nada aqui é refeito pelo modelo.
     *
     * <p>O registro vem <b>depois</b> da execução e nunca a derruba: a ação já aconteceu no PC ou
     * no disco, e o diário é a testemunha dela, não o guarda. Falhar aqui perderia a linha do
     * diário; lançar perderia a resposta de uma ação que já foi feita.
     */
    private String rodar(Pendente p, String canal) {
        String resposta = p.executar().get();
        TipoDeAcao tipo = tipoNoDiario(p.tipo());
        if (diario != null && tipo != null) {
            try {
                diario.registrar(tipo, p.descricao(), OrigemDaAcao.porCanal(canal));
            } catch (RuntimeException e) {
                log.warn("Ação aprovada '{}' executada, mas não entrou no diário: {}",
                        p.tipo(), e.getMessage());
            }
        }
        return resposta;
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
