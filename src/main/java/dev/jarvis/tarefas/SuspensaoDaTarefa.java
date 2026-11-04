package dev.jarvis.tarefas;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import dev.jarvis.ferramentas.ResultadoFerramenta;

/** Uma ferramenta parada antes de produzir seu resultado; a continuação permanece só em memória. */
public final class SuspensaoDaTarefa extends ControleDaTarefa {
    public enum Tipo { APROVACAO, PC, OPERACAO_PC, COMPUTER_USE, SITE, HORARIO }
    private final Tipo tipo;
    private final String alvo;
    private final Instant ate;
    private final BooleanSupplier pronta;
    private final Callable<ResultadoFerramenta> continuar;
    private final boolean repetirFerramenta;
    private final Pergunta pergunta;
    /** O desfecho de uma espera de aprovação, lido pelo executor antes de continuar. */
    public record Pergunta(BooleanSupplier recusada, BooleanSupplier semResposta, Runnable renovar) { }

    public SuspensaoDaTarefa(Tipo tipo, String alvo, Instant ate, BooleanSupplier pronta,
            Callable<ResultadoFerramenta> continuar) {
        this(tipo, alvo, ate, pronta, continuar, false);
    }
    /**
     * @param repetirFerramenta a ferramenta não iniciou nada (o PC estava fora): na volta ela roda
     *                          de novo, em vez de receber o {@code continuar} como resultado.
     */
    public SuspensaoDaTarefa(Tipo tipo, String alvo, Instant ate, BooleanSupplier pronta,
            Callable<ResultadoFerramenta> continuar, boolean repetirFerramenta) {
        this(tipo, alvo, ate, pronta, continuar, repetirFerramenta, null);
    }
    public SuspensaoDaTarefa(Tipo tipo, String alvo, Instant ate, BooleanSupplier pronta,
            Callable<ResultadoFerramenta> continuar, Pergunta pergunta) {
        this(tipo, alvo, ate, pronta, continuar, false, pergunta);
    }
    private SuspensaoDaTarefa(Tipo tipo, String alvo, Instant ate, BooleanSupplier pronta,
            Callable<ResultadoFerramenta> continuar, boolean repetirFerramenta, Pergunta pergunta) {
        super("Aguardando " + tipo.name().toLowerCase());
        this.tipo = tipo; this.alvo = alvo; this.ate = ate;
        this.pronta = pronta; this.continuar = continuar; this.repetirFerramenta = repetirFerramenta;
        this.pergunta = pergunta;
    }
    /** Só nas esperas de aprovação; nulo nas outras. */
    public Pergunta pergunta() { return pergunta; }
    public boolean repetirFerramenta() { return repetirFerramenta; }
    public Tipo tipo() { return tipo; }
    public String alvo() { return alvo; }
    public Instant ate() { return ate; }
    public boolean pronta() { return pronta.getAsBoolean(); }
    public ResultadoFerramenta continuar() throws Exception { return continuar.call(); }
}
