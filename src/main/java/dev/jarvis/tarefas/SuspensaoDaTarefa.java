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

    public SuspensaoDaTarefa(Tipo tipo, String alvo, Instant ate, BooleanSupplier pronta,
            Callable<ResultadoFerramenta> continuar) {
        super("Aguardando " + tipo.name().toLowerCase());
        this.tipo = tipo; this.alvo = alvo; this.ate = ate;
        this.pronta = pronta; this.continuar = continuar;
    }
    public Tipo tipo() { return tipo; }
    public String alvo() { return alvo; }
    public Instant ate() { return ate; }
    public boolean pronta() { return pronta.getAsBoolean(); }
    public ResultadoFerramenta continuar() throws Exception { return continuar.call(); }
}
