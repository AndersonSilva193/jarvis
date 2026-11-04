package dev.jarvis.tarefas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.CicloDeFerramentas;
import dev.jarvis.ferramentas.ResultadoFerramenta;

/** O retrato persistente fica separado das capacidades e mensagens mantidas em memória. */
public final class TarefaDeFundo {
    public enum Status { AGUARDANDO_APROVACAO, NA_FILA, RODANDO, ESPERANDO_VOCE,
        ESPERANDO_PC, PAUSADA, CONCLUIDA, FALHOU, CANCELADA }
    public enum Origem { PEDIDO, GATILHO }
    /** A pausa que só o {@code retomar_tarefa} desfaz: o dono pediu, ou a pergunta ficou sem resposta. */
    public static final String PAUSA_DONO = "dono", PAUSA_SEM_RESPOSTA = "sem_resposta";
    public record AcaoPrevista(String chave, String alvo) {
        public AcaoPrevista {
            var acao = AcaoAutonoma.porChave(chave);
            if (acao == null) throw new IllegalArgumentException("Ação desconhecida: " + chave);
            // Ação sem alvo vale inteira na política, então descartar um alvo enfeitado não amplia nada.
            alvo = alvo == null || alvo.isBlank() || !acao.aceitaAlvo() ? null : alvo.strip();
            if (acao.aceitaAlvo() && (alvo == null || !alvo.matches("[a-z0-9][a-z0-9_.-]{0,31}")))
                throw new IllegalArgumentException("Alvo inválido para " + chave
                        + ": informe o nome exato da rotina, em minúsculas e sem espaços.");
        }
        public AcaoAutonoma acao() { return AcaoAutonoma.porChave(chave); }
    }
    public record Retrato(String id, long usuario, String canal, long chat, String objetivo,
            List<String> plano, List<AcaoPrevista> acoesPrevistas, Status status,
            List<String> resumo, String resultado, Instant criadaEm, Instant iniciadaEm,
            Instant atualizadaEm, Instant concluidaEm, int passos, long tempoAtivoMillis,
            double custoUsd, Origem origem, boolean retomada, boolean planoAprovado,
            String esperaTipo, String esperaAlvo, Instant retomarEm, java.util.Map<String, String> comandos,
            String pausa, Instant esperaDesde) { }

    final String id, objetivo;
    final long usuario, chat;
    final Canal canal;
    final List<String> plano;
    final List<AcaoPrevista> acoes;
    final Origem origem;
    final Instant criadaEm;
    volatile Status status;
    final List<String> resumo = new ArrayList<>();
    String resultado = "", esperaTipo = "", esperaAlvo = "";
    volatile String falhaOrcamento = "";
    volatile Instant iniciadaEm, atualizadaEm, concluidaEm, ativoDesde, retomarEm;
    /** Desde quando espera o PC (persistido: o teto de espera vale também depois de um restart). */
    volatile Instant esperaDesde;
    volatile String pausa = "";
    /** A frase final no lugar da de sempre; vazia quando quem cancelou já respondeu ao dono. */
    volatile String avisoFinal;
    volatile int passos;
    int registrosAntes;
    volatile long tempoAtivoMillis;
    volatile double custoUsd;
    volatile boolean retomada, planoAprovado, agendada;
    boolean finalizando;
    boolean resultadoProvisorio;
    volatile boolean cancelar, pausar;
    volatile Thread executora;
    CicloDeFerramentas.Execucao ciclo;
    volatile SuspensaoDaTarefa espera;
    volatile dev.jarvis.aprovacao.Aprovacoes.AutorizacaoDaTarefa aprovacaoInicial;
    final List<ResultadoFerramenta> detalhados = new ArrayList<>();
    final ContextoDaTarefa.Dados dados = new ContextoDaTarefa.Dados();
    final java.util.Map<String, String> comandos = new java.util.concurrent.ConcurrentHashMap<>();
    dev.jarvis.conversa.ContextoArquivo contextoArquivo;

    TarefaDeFundo(String id, long usuario, Canal canal, long chat, String objetivo, List<String> plano,
            List<AcaoPrevista> acoes, Origem origem, Instant agora) {
        this.id = id; this.usuario = usuario; this.canal = canal; this.chat = chat;
        this.objetivo = objetivo; this.plano = List.copyOf(plano); this.acoes = List.copyOf(acoes);
        this.origem = java.util.Objects.requireNonNull(origem);
        criadaEm = java.util.Objects.requireNonNull(agora); atualizadaEm = agora; status = Status.NA_FILA;
    }
    boolean encerrada() { return status == Status.CONCLUIDA || status == Status.FALHOU || status == Status.CANCELADA; }
    long tempoAtivo(Instant agora) {
        return tempoAtivoMillis + (ativoDesde == null ? 0 : Math.max(0, java.time.Duration.between(ativoDesde, agora).toMillis()));
    }
    Retrato retrato(Instant agora) {
        return new Retrato(id, usuario, canal.nome(), chat, objetivo, plano, acoes, status,
                List.copyOf(resumo), resultado, criadaEm, iniciadaEm, atualizadaEm, concluidaEm,
                passos, tempoAtivo(agora), custoUsd, origem, retomada, planoAprovado,
                esperaTipo, esperaAlvo, retomarEm, java.util.Map.copyOf(comandos), pausa, esperaDesde);
    }
}
