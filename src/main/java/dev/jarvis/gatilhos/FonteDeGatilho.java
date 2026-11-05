package dev.jarvis.gatilhos;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * De onde vem o evento que uma regra escuta. Catálogo fechado: cada fonte é publicada pelo serviço
 * que já sabe do fato, e o modelo escolhe uma destas, nunca inventa outra.
 *
 * <p>A {@link #chave()} é o que vai para o esquema da ferramenta e para o {@code gatilhos.json},
 * explícita pelo motivo da {@code Recorrencia}: renomear a constante não pode invalidar regra
 * gravada.
 */
public enum FonteDeGatilho {
    SITE_CAIU("site_caiu", Set.of("site")),
    SITE_VOLTOU("site_voltou", Set.of("site")),
    PC_LIGOU("pc_ligou", Set.of()),
    PC_DESLIGOU("pc_desligou", Set.of()),
    COTA_VOZ("cota_voz", Set.of("tipo")),
    ARQUIVO_NOVO_NA_ENTRADA("arquivo_novo_na_entrada", Set.of("nome", "formato")),
    TAREFA_FALHOU("tarefa_falhou", Set.of()),
    HORARIO("horario", Set.of("quando", "recorrencia", "dias_da_semana"));

    private final String chave;
    private final Set<String> campos;

    FonteDeGatilho(String chave, Set<String> campos) {
        this.chave = chave;
        this.campos = campos;
    }

    public String chave() {
        return chave;
    }

    /** Os campos do filtro que esta fonte entende; qualquer outro é recusado na criação. */
    public Set<String> camposDoFiltro() {
        return campos;
    }

    public static List<String> chaves() {
        return Arrays.stream(values()).map(FonteDeGatilho::chave).toList();
    }

    /** A fonte daquela chave, ou {@code null} — chave desconhecida é linha a descartar. */
    public static FonteDeGatilho porChave(String chave) {
        if (chave == null) return null;
        String limpa = chave.strip().toLowerCase(java.util.Locale.ROOT);
        for (FonteDeGatilho f : values()) if (f.chave.equals(limpa)) return f;
        return null;
    }
}
