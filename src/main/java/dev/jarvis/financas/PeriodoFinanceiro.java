package dev.jarvis.financas;

import java.time.LocalDate;
import java.util.Locale;

/**
 * Os períodos do resumo financeiro, num conjunto fechado.
 *
 * <p>Enum e não "quantos dias", pelo motivo da {@code acoes_do_jarvis}: um inteiro livre convida o
 * modelo a pedir 3.650 dias, e o que o dono pergunta cabe nestes cinco.
 */
public enum PeriodoFinanceiro {

    MES_ATUAL("mes_atual", "deste mês"),
    MES_PASSADO("mes_passado", "do mês passado"),
    ULTIMOS_30_DIAS("ultimos_30_dias", "dos últimos 30 dias"),
    ANO_ATUAL("ano_atual", "deste ano"),
    TUDO("tudo", "de todo o histórico");

    private final String chave;
    private final String falado;

    PeriodoFinanceiro(String chave, String falado) {
        this.chave = chave;
        this.falado = falado;
    }

    public String chave() {
        return chave;
    }

    /** Como o período entra na frase: "Resumo <b>deste mês</b>". */
    public String falado() {
        return falado;
    }

    /** As chaves, na ordem, para o {@code enum} dos esquemas. */
    public static String[] chaves() {
        PeriodoFinanceiro[] todos = values();
        String[] chaves = new String[todos.length];
        for (int i = 0; i < todos.length; i++) {
            chaves[i] = todos[i].chave;
        }
        return chaves;
    }

    /** O período daquela chave, ou {@code null}. Vazio vale o mês atual, a pergunta mais comum. */
    public static PeriodoFinanceiro porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return MES_ATUAL;
        }
        String procurada = chave.trim().toLowerCase(Locale.ROOT);
        for (PeriodoFinanceiro periodo : values()) {
            if (periodo.chave.equals(procurada)) {
                return periodo;
            }
        }
        return null;
    }

    /** O primeiro dia do período, inclusive. */
    public LocalDate inicio(LocalDate hoje) {
        return switch (this) {
            case MES_ATUAL -> hoje.withDayOfMonth(1);
            case MES_PASSADO -> hoje.minusMonths(1).withDayOfMonth(1);
            case ULTIMOS_30_DIAS -> hoje.minusDays(29);
            case ANO_ATUAL -> hoje.withDayOfYear(1);
            case TUDO -> LocalDate.MIN;
        };
    }

    /** O último dia do período, inclusive. */
    public LocalDate fim(LocalDate hoje) {
        return switch (this) {
            case MES_PASSADO -> hoje.withDayOfMonth(1).minusDays(1);
            default -> hoje;
        };
    }
}
