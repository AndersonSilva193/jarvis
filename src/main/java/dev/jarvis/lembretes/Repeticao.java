package dev.jarvis.lembretes;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A regra de repetição de um lembrete: o tipo mais os parâmetros que aquele tipo pede.
 *
 * O dia do mês é guardado à parte da data do lembrete de propósito. "Todo dia 31" cai em 28 de
 * fevereiro, e se a próxima ocorrência fosse calculada a partir dessa data o lembrete viraria
 * "todo dia 28" para sempre — o pedido original tem de sobreviver ao mês curto.
 */
public record Repeticao(Recorrencia tipo, Set<DayOfWeek> diasDaSemana, int diaDoMes) {

    /** Lembrete único: dispara uma vez e acabou. */
    public static final Repeticao NENHUMA = new Repeticao(Recorrencia.NENHUMA, Set.of(), 0);

    public Repeticao {
        tipo = tipo == null ? Recorrencia.NENHUMA : tipo;
        diasDaSemana = diasDaSemana == null || diasDaSemana.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(diasDaSemana));
        if (tipo == Recorrencia.SEMANAL && diasDaSemana.isEmpty()) {
            throw new IllegalArgumentException("recorrência semanal precisa de pelo menos um dia da semana");
        }
        if (tipo == Recorrencia.MENSAL && (diaDoMes < 1 || diaDoMes > 31)) {
            throw new IllegalArgumentException("dia do mês precisa estar entre 1 e 31 (recebi " + diaDoMes + ")");
        }
        if (tipo != Recorrencia.MENSAL) {
            diaDoMes = 0;
        }
        if (tipo != Recorrencia.SEMANAL) {
            diasDaSemana = Set.of();
        }
    }

    public static Repeticao diario() {
        return new Repeticao(Recorrencia.DIARIO, Set.of(), 0);
    }

    public static Repeticao diasUteis() {
        return new Repeticao(Recorrencia.DIAS_UTEIS, Set.of(), 0);
    }

    public static Repeticao semanal(Set<DayOfWeek> dias) {
        return new Repeticao(Recorrencia.SEMANAL, dias, 0);
    }

    public static Repeticao mensal(int diaDoMes) {
        return new Repeticao(Recorrencia.MENSAL, Set.of(), diaDoMes);
    }

    public boolean recorrente() {
        return tipo != Recorrencia.NENHUMA;
    }

    /**
     * A primeira ocorrência depois de {@code atual}, no mesmo horário e fuso.
     *
     * Vazio quando o lembrete não repete. O horário vem de {@code atual} e não de um campo
     * separado: é a hora que o dono pediu, e ela só muda se ele recriar o lembrete.
     */
    public Optional<ZonedDateTime> proxima(ZonedDateTime atual) {
        if (!recorrente()) {
            return Optional.empty();
        }
        LocalTime hora = atual.toLocalTime();
        LocalDate data = switch (tipo) {
            case DIARIO -> atual.toLocalDate().plusDays(1);
            case DIAS_UTEIS -> proximoDiaUtil(atual.toLocalDate());
            case SEMANAL -> proximoDiaDaSemana(atual.toLocalDate());
            case MENSAL -> noMes(atual.toLocalDate().withDayOfMonth(1).plusMonths(1));
            case NENHUMA -> throw new IllegalStateException("tipo nenhuma já tratado acima");
        };
        // atZone resolve horário inexistente de virada de horário de verão empurrando para frente,
        // que é o comportamento que queremos: o lembrete atrasa minutos, não desaparece.
        return Optional.of(data.atTime(hora).atZone(atual.getZone()));
    }

    /**
     * Se a série tem uma ocorrência na data indicada.
     *
     * É isso que faz o briefing e o card da HUD mostrarem o recorrente de hoje mesmo quando a
     * próxima ocorrência persistida já pulou para amanhã (lembrete das 8h visto às 10h).
     */
    public boolean ocorreEm(LocalDate data) {
        return switch (tipo) {
            case NENHUMA -> false;
            case DIARIO -> true;
            case DIAS_UTEIS -> !fimDeSemana(data);
            case SEMANAL -> diasDaSemana.contains(data.getDayOfWeek());
            case MENSAL -> data.getDayOfMonth() == Math.min(diaDoMes, data.lengthOfMonth());
        };
    }

    /** Como a recorrência é dita ao dono, na listagem e no briefing. Vazio quando não repete. */
    public String descricao() {
        return switch (tipo) {
            case NENHUMA -> "";
            case DIARIO -> "todos os dias";
            case DIAS_UTEIS -> "dias úteis";
            case SEMANAL -> "toda " + nomesDosDias();
            case MENSAL -> "dia " + diaDoMes + " de cada mês";
        };
    }

    private String nomesDosDias() {
        StringJoiner nomes = new StringJoiner(", ");
        for (DayOfWeek dia : diasOrdenados()) {
            nomes.add(switch (dia) {
                case MONDAY -> "segunda";
                case TUESDAY -> "terça";
                case WEDNESDAY -> "quarta";
                case THURSDAY -> "quinta";
                case FRIDAY -> "sexta";
                case SATURDAY -> "sábado";
                case SUNDAY -> "domingo";
            });
        }
        return nomes.toString();
    }

    /** Segunda a domingo, para a descrição e o arquivo não mudarem de ordem entre execuções. */
    public List<DayOfWeek> diasOrdenados() {
        List<DayOfWeek> ordenados = new ArrayList<>(diasDaSemana);
        ordenados.sort(null);
        return ordenados;
    }

    private static LocalDate proximoDiaUtil(LocalDate desde) {
        LocalDate candidato = desde.plusDays(1);
        while (fimDeSemana(candidato)) {
            candidato = candidato.plusDays(1);
        }
        return candidato;
    }

    private LocalDate proximoDiaDaSemana(LocalDate desde) {
        LocalDate candidato = desde.plusDays(1);
        // No máximo sete passos: o conjunto nunca é vazio (o construtor garante).
        for (int i = 0; i < 7; i++) {
            if (diasDaSemana.contains(candidato.getDayOfWeek())) {
                return candidato;
            }
            candidato = candidato.plusDays(1);
        }
        throw new IllegalStateException("conjunto de dias da semana vazio");
    }

    /** O dia pedido dentro do mês de {@code primeiroDoMes}, encurtado ao último dia se não existir. */
    private LocalDate noMes(LocalDate primeiroDoMes) {
        return primeiroDoMes.withDayOfMonth(Math.min(diaDoMes, primeiroDoMes.lengthOfMonth()));
    }

    private static boolean fimDeSemana(LocalDate data) {
        DayOfWeek dia = data.getDayOfWeek();
        return dia == DayOfWeek.SATURDAY || dia == DayOfWeek.SUNDAY;
    }
}
