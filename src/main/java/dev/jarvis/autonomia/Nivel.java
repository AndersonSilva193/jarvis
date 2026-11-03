package dev.jarvis.autonomia;

/**
 * O que o Jarvis faz com uma ação do catálogo. A ordem das constantes é a ordem de rigor, e é ela
 * que o piso compara: um nível só vale se não estiver <b>antes</b> do piso da ação.
 *
 * <p>A {@link #secao()} é o título da seção no {@code config/autonomia.md}; a constante Java pode
 * mudar de nome sem quebrar o arquivo do dono.
 */
public enum Nivel {

    FAZ_E_AVISA("faz_e_avisa"),
    PERGUNTA("pergunta_antes"),
    PROIBIDO("proibido");

    private final String secao;

    Nivel(String secao) {
        this.secao = secao;
    }

    public String secao() {
        return secao;
    }

    /** Se este nível é pelo menos tão rigoroso quanto o outro. */
    public boolean peloMenos(Nivel outro) {
        return ordinal() >= outro.ordinal();
    }

    /** O nível daquela seção, ou {@code null} — seção desconhecida é linha a ignorar, não erro. */
    public static Nivel daSecao(String titulo) {
        String procurado = titulo == null ? "" : titulo.strip().toLowerCase(java.util.Locale.ROOT);
        for (Nivel nivel : values()) {
            if (nivel.secao.equals(procurado)) {
                return nivel;
            }
        }
        return null;
    }
}
