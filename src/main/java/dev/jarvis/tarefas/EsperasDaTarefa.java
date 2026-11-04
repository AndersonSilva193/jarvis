package dev.jarvis.tarefas;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.PcVoltou;
import org.springframework.context.event.EventListener;

/** Condições fechadas, verificadas sem gastar chamadas do modelo. */
@Component
public class EsperasDaTarefa {
    private final PcService pc;
    private final OperacoesPcService operacoes;
    private final SitesService sites;
    private final ObjectProvider<TarefasComputerUse> computer;
    private final Clock relogio;
    private final ExecutorService verificacoes = Executors.newFixedThreadPool(2,
            Thread.ofVirtual().name("espera-de-site-", 0).factory());
    private final Map<String, Verificacao> verificacoesDeSites = new ConcurrentHashMap<>();
    /** O último {@link PcVoltou}; a espera pelo PC fica pronta com um retorno posterior ao início dela. */
    private volatile Instant ultimoRetorno = Instant.MIN;
    private static final class Verificacao {
        final AtomicBoolean emCurso = new AtomicBoolean();
        volatile boolean disponivel;
        volatile Instant proxima = Instant.MIN;
    }
    @Autowired
    public EsperasDaTarefa(PcService pc, OperacoesPcService operacoes, SitesService sites,
            ObjectProvider<TarefasComputerUse> computer) { this(pc, operacoes, sites, computer, Clock.systemUTC()); }
    public EsperasDaTarefa(PcService pc, OperacoesPcService operacoes, SitesService sites,
            ObjectProvider<TarefasComputerUse> computer, Clock relogio) {
        this.pc = pc; this.operacoes = operacoes; this.sites = sites; this.computer = computer; this.relogio = relogio;
    }
    public boolean pcDisponivel() { return pc.online(); }
    /** Só marca: quem publica é o thread do agente do PC, e a tarefa é retomada pelo {@code varrer}. */
    @EventListener public void pcVoltou(PcVoltou evento) { ultimoRetorno = evento.em(); }
    /** O {@code aguardar}: um horário relativo, com o motivo no alvo para a listagem do dono. */
    public SuspensaoDaTarefa aguardar(java.time.Duration quanto, String motivo) {
        return criar(SuspensaoDaTarefa.Tipo.HORARIO, motivo, relogio.instant().plus(quanto));
    }
    /** A ferramenta não pôde nem começar com o PC fora: espera a volta e roda de novo. */
    public SuspensaoDaTarefa pcOffline() { return esperaPeloPc("", true); }
    public void cancelarOperacao(String id) { pc.cancelar(id); }
    public void cancelarComputer(String id, long usuario) { computer.getObject().cancelar(id, usuario); }
    /** Null significa comando não recuperável, vazio ainda em andamento; o resto é estado final. */
    public String reconciliar(String id) {
        var estado = operacoes.estado(id).orElse(null);
        if (estado == null) return null;
        if (List.of("pendente", "entregue", "recebido", "executando").contains(estado.estado())) return "";
        return estado.estado() + ": " + estado.mensagem();
    }
    public boolean usaPc(String ferramenta) {
        return Set.of("controlar_pc", "rodar_rotina", "tarefa_no_pc", "listar_arquivos", "ler_arquivo",
                "editar_arquivo", "criar_arquivo", "desfazer_edicao", "apagar_arquivo", "restaurar_arquivo").contains(ferramenta);
    }
    public SuspensaoDaTarefa criar(SuspensaoDaTarefa.Tipo tipo, String alvo, Instant ate) {
        return criar(tipo, alvo, ate, ContextoDaTarefa.atual() == null ? 0 : ContextoDaTarefa.atual().usuario());
    }
    public SuspensaoDaTarefa criar(SuspensaoDaTarefa.Tipo tipo, String alvo, Instant ate, long usuario) {
        return switch (tipo) {
            case PC -> esperaPeloPc(alvo, false);
            case HORARIO -> {
                if (ate == null) throw new IllegalArgumentException("Horário ISO-8601 com fuso necessário.");
                yield new SuspensaoDaTarefa(tipo, alvo == null ? "" : alvo, ate, () -> !relogio.instant().isBefore(ate),
                        () -> ResultadoFerramenta.texto("Horário solicitado alcançado."));
            }
            case SITE -> {
                if (!sites.sites().containsKey(alvo)) throw new IllegalArgumentException("Site fora da lista configurada.");
                yield new SuspensaoDaTarefa(tipo, alvo, null, () -> siteDisponivel(alvo),
                        () -> ResultadoFerramenta.texto("O site " + alvo + " está disponível."));
            }
            case OPERACAO_PC -> {
                if (usuario <= 0 || operacoes.chatId(alvo) != usuario || operacoes.estado(alvo).isEmpty())
                    throw new IllegalArgumentException("Operação não encontrada para este usuário.");
                yield new SuspensaoDaTarefa(tipo, alvo, null,
                        () -> { String estado = reconciliar(alvo); return estado == null || !estado.isEmpty(); },
                        () -> ResultadoFerramenta.texto("Operação " + alvo + ": " + operacoes.resultado(alvo)
                                .map(dev.jarvis.pc.ResultadoPc::mensagem).orElseGet(() -> {
                                    String estado = reconciliar(alvo);
                                    return (estado == null ? "sem rastreio" : estado)
                                            + "; confira o estado antes de repetir qualquer ação.";
                                })));
            }
            case COMPUTER_USE -> new SuspensaoDaTarefa(tipo, alvo, null, () -> {
                var t = computer.getObject().estado(alvo, usuario);
                return t == null || List.of("concluido", "falhou", "cancelado", "resultado_incerto").contains(t.estado());
            }, () -> {
                var t = computer.getObject().estado(alvo, usuario);
                return ResultadoFerramenta.texto(t == null
                        ? "Sessão do PC perdida após reinício; confira o estado da tela antes de agir."
                        : "Tarefa no PC " + t.estado() + ": " + t.motivo());
            });
            case APROVACAO -> throw new IllegalArgumentException("Aprovação precisa de uma nova pergunta autenticada.");
        };
    }
    /**
     * "computer_livre" continua olhando o estado direto: ali o PC já está no ar e não haverá volta.
     * A espera comum fica pronta com um {@code PcVoltou} igual ou posterior ao instante em que ela
     * começou — o que fecha a corrida entre a checagem de offline e o contato que chega logo depois.
     * Já no ar na criação (o {@code aguardar_tarefa} pedido com o PC ligado, a partida) vale na hora.
     */
    private SuspensaoDaTarefa esperaPeloPc(String alvo, boolean repetirFerramenta) {
        Instant desde = relogio.instant();
        boolean jaNoAr = pc.online();
        java.util.function.BooleanSupplier pronta = "computer_livre".equals(alvo)
                ? () -> pc.online() && !computer.getObject().ocupada()
                : () -> pc.online() && (jaNoAr || !ultimoRetorno.isBefore(desde));
        return new SuspensaoDaTarefa(SuspensaoDaTarefa.Tipo.PC, alvo, null, pronta,
                () -> ResultadoFerramenta.texto("PC disponível."), repetirFerramenta);
    }
    private boolean siteDisponivel(String nome) {
        var v = verificacoesDeSites.computeIfAbsent(nome, n -> new Verificacao());
        if (!relogio.instant().isBefore(v.proxima)) {
            if (v.emCurso.compareAndSet(false, true)) verificacoes.submit(() -> {
                    try { v.disponivel = sites.situacaoDe(sites.sites().get(nome)).ok(); }
                    finally { v.proxima = relogio.instant().plusSeconds(30); v.emCurso.set(false); }
                });
            return false;
        }
        return v.disponivel;
    }
    @jakarta.annotation.PreDestroy public void fechar() { verificacoes.shutdownNow(); }
}
