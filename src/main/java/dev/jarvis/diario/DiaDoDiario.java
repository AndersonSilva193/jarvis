package dev.jarvis.diario;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * O acumulador de um dia do diário, espelho do {@code EstadoDoDia} do custo — com a diferença de
 * forma que define este pacote: o custo soma, e isto <b>lista</b>.
 *
 * <p>É daí que vem o teto. O javadoc do {@code CustoDiarioEmArquivo} avisa que um registro por
 * evento viraria megabytes por semana, e ali a saída foi agregar. Aqui não dá: "o que você fez
 * hoje?" quer os fatos, um por um. Então o dia para de crescer em {@link #MAX_ACOES} e passa a
 * contar o que recusou — um dia truncado que se declara truncado é honesto, e a ferramenta diz
 * quantas ficaram de fora.
 */
final class DiaDoDiario {

    /**
     * Quantas ações cabem num dia.
     *
     * Quinhentas linhas de até 120 caracteres dão uns 60 KB de JSON, bem dentro do
     * {@code MAX_BYTES} da leitura. Um dia honesto do Jarvis tem dezenas; passar de quinhentas é
     * laço de ferramenta, não trabalho — e nesse caso o que interessa é o diário continuar legível.
     */
    static final int MAX_ACOES = 500;

    private final LocalDate dia;
    private final List<AcaoRegistrada> acoes;
    private int descartadas;

    DiaDoDiario(LocalDate dia) {
        this(dia, new ArrayList<>(), 0);
    }

    private DiaDoDiario(LocalDate dia, List<AcaoRegistrada> acoes, int descartadas) {
        this.dia = dia;
        this.acoes = acoes;
        this.descartadas = descartadas;
    }

    LocalDate dia() {
        return dia;
    }

    /** Em ordem de registro: o diário é cronológico, e quem quer o contrário inverte na leitura. */
    List<AcaoRegistrada> acoes() {
        return acoes;
    }

    int descartadas() {
        return descartadas;
    }

    boolean vazio() {
        return acoes.isEmpty() && descartadas == 0;
    }

    /** Acrescenta, ou conta a recusa. Devolve {@code false} quando o dia já estava cheio. */
    boolean somar(AcaoRegistrada acao) {
        if (acoes.size() >= MAX_ACOES) {
            descartadas++;
            return false;
        }
        acoes.add(acao);
        return true;
    }

    /**
     * Conta um descarte sem ter a ação em mão. É por aqui que o dia truncado continua se
     * declarando truncado depois de um restart, relendo o próprio contador do JSON.
     */
    void contarDescartada() {
        descartadas++;
    }

    /** Cópia rasa-mas-segura para a fila de gravação: {@link AcaoRegistrada} é imutável. */
    DiaDoDiario copia() {
        return new DiaDoDiario(dia, new ArrayList<>(acoes), descartadas);
    }
}
