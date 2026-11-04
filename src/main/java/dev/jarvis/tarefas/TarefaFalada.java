package dev.jarvis.tarefas;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import static dev.jarvis.tarefas.TarefaDeFundo.Status.*;

/** O retrato de uma tarefa dito em português, curto: o modelo lê isto e responde ao dono. */
public final class TarefaFalada {
    private TarefaFalada() { }

    public static boolean ativa(TarefaDeFundo.Retrato t) {
        return t.status() != CONCLUIDA && t.status() != FALHOU && t.status() != CANCELADA;
    }

    public static String status(TarefaDeFundo.Retrato t, Instant agora, ZoneId zona) {
        String desde = " há " + duracao(t.atualizadaEm(), agora);
        return switch (t.status()) {
            case AGUARDANDO_APROVACAO -> "esperando sua aprovação para começar";
            case NA_FILA -> t.esperaTipo().equals("HORARIO") && t.retomarEm() != null
                    ? "aguardando até " + DateTimeFormatter.ofPattern("HH:mm").format(t.retomarEm().atZone(zona))
                            + (t.esperaAlvo().isBlank() ? "" : " (" + t.esperaAlvo() + ")")
                    : t.esperaTipo().equals("SITE") ? "esperando o site " + t.esperaAlvo() + " voltar" : "na fila";
            case RODANDO -> "em andamento";
            case ESPERANDO_VOCE -> "esperando sua resposta";
            case ESPERANDO_PC -> switch (t.esperaTipo()) {
                case "OPERACAO_PC" -> "esperando uma operação no PC terminar";
                case "COMPUTER_USE" -> "esperando a tarefa na tela do PC";
                default -> "esperando o PC voltar";
            } + desde;
            case PAUSADA -> TarefaDeFundo.PAUSA_DONO.equals(t.pausa()) ? "pausada por você" + desde
                    : TarefaDeFundo.PAUSA_SEM_RESPOSTA.equals(t.pausa()) ? "esperando sua resposta (a pergunta expirou)"
                    : "pausada (trabalho autônomo pausado)";
            case CONCLUIDA -> "concluída";
            case FALHOU -> "falhou";
            case CANCELADA -> "cancelada";
        };
    }

    /** Uma linha: objetivo, status, passo atual e há quanto tempo; o id vai para o modelo referenciar. */
    public static String linha(TarefaDeFundo.Retrato t, Instant agora, ZoneId zona) {
        StringBuilder s = new StringBuilder("- ").append(cortar(t.objetivo(), 90)).append(" — ")
                .append(status(t, agora, zona));
        if (ativa(t)) {
            if (t.passos() > 0) {
                s.append(", passo ").append(t.passos());
                String ultimo = ultimaFerramenta(t);
                if (!ultimo.isBlank()) s.append(" (").append(ultimo).append(")");
            }
            s.append(", começou há ").append(duracao(t.criadaEm(), agora));
        } else {
            Instant fim = t.concluidaEm() == null ? t.atualizadaEm() : t.concluidaEm();
            s.append(" há ").append(duracao(fim, agora));
            if (!t.resultado().isBlank()) s.append(": ").append(cortar(t.resultado(), 140));
        }
        return s.append(" [id ").append(t.id()).append("]").toString();
    }

    private static String ultimaFerramenta(TarefaDeFundo.Retrato t) {
        if (t.resumo().isEmpty()) return "";
        String ultimo = t.resumo().getLast();
        int fim = ultimo.indexOf(':');
        String nome = fim < 0 ? "" : ultimo.substring(0, fim);
        return nome.matches("[a-z_]{2,40}") ? nome : "";
    }

    public static String duracao(Instant de, Instant ate) {
        long s = de == null ? 0 : Math.max(0, Duration.between(de, ate).toSeconds());
        if (s < 60) return "menos de 1 min";
        if (s < 3600) return (s / 60) + " min";
        if (s < 86_400) return (s / 3600) + " h" + (s % 3600 >= 60 ? " " + (s % 3600) / 60 + " min" : "");
        return (s / 86_400) + (s / 86_400 == 1 ? " dia" : " dias");
    }

    static String cortar(String texto, int max) {
        String limpo = texto == null ? "" : texto.replaceAll("\\s+", " ").strip();
        return limpo.length() <= max ? limpo : limpo.substring(0, max - 1) + "…";
    }
}
