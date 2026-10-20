package dev.jarvis.lembretes;

import java.util.Arrays;
import java.util.List;

/**
 * Com que frequência um lembrete volta.
 *
 * A {@link #chave()} é o que o modelo escreve no JSON da ferramenta e o que vai para o arquivo —
 * por isso ela é explícita e não {@code name().toLowerCase()}: renomear a constante em Java não
 * pode invalidar os lembretes já gravados em disco.
 */
public enum Recorrencia {

    NENHUMA("nenhuma"),
    DIARIO("diario"),
    DIAS_UTEIS("dias_uteis"),
    SEMANAL("semanal"),
    MENSAL("mensal");

    private final String chave;

    Recorrencia(String chave) {
        this.chave = chave;
    }

    public String chave() {
        return chave;
    }

    /** As chaves na ordem do enum, para o {@code enum} do esquema da ferramenta. */
    public static List<String> chaves() {
        return Arrays.stream(values()).map(Recorrencia::chave).toList();
    }

    /** Vazio ou nulo vira {@link #NENHUMA}: ferramenta sem o campo cria lembrete único. */
    public static Recorrencia porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return NENHUMA;
        }
        String limpa = chave.trim().toLowerCase();
        for (Recorrencia r : values()) {
            if (r.chave.equals(limpa)) {
                return r;
            }
        }
        throw new IllegalArgumentException("recorrência inválida: '" + chave + "'. Use uma de " + chaves());
    }
}
