package dev.jarvis.aprovacao;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.Confirmacao;

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
    private Pendente pendente;

    public Aprovacoes() { this(Clock.systemUTC()); }
    public Aprovacoes(Clock relogio) { this.relogio = relogio; }

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
        return Optional.of(executar.executar().get());
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
        Pendente executar;
        synchronized (this) {
            if (pendenciaDe(usuario, pedidoAtual).isEmpty()) return Optional.empty();
            executar = pendente;
            pendente = null;
        }
        return Optional.of(executar.executar().get());
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
