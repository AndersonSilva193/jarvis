package dev.jarvis.diario;

/**
 * O que o Jarvis fez. A constante é Java ({@code EDICAO_ARQUIVO}); a {@link #chave()} é o que vai
 * para o JSON do dia ({@code edicao_arquivo}).
 *
 * É o mesmo par de {@link dev.jarvis.custo.Origem} e de {@link dev.jarvis.lembretes.Recorrencia},
 * e existe pelo mesmo motivo: o diário de ontem tem de continuar legível amanhã, mesmo que alguém
 * renomeie uma constante.
 *
 * <p>O {@link #autonoma()} separa as duas metades do diário, e é o que a seção do briefing filtra:
 * <b>autônoma</b> é o que o Jarvis fez sem perguntar, e é disso que o dono não tem como saber;
 * o resto ele aprovou de viva voz, e já sabe. O {@link #rotulo()} é o plural que a contagem fala
 * ("3 edições de arquivo") — vai para voz, então nada de nome de constante.
 */
public enum TipoDeAcao {

    // ---- o que ele faz sozinho ("faz e avisa")
    EDICAO_ARQUIVO("edicao_arquivo", "edição de arquivo", "edições de arquivo", true),
    DESFAZER_EDICAO("desfazer_edicao", "edição desfeita", "edições desfeitas", true),
    RESTAURAR_ARQUIVO("restaurar_arquivo", "arquivo restaurado", "arquivos restaurados", true),
    CRIAR_ARQUIVO("criar_arquivo", "arquivo criado", "arquivos criados", true),
    ANOTAR_MEMORIA("anotar_memoria", "anotação na memória", "anotações na memória", true),
    ATUALIZAR_MEMORIA("atualizar_memoria", "memória corrigida", "memórias corrigidas", true),

    // ---- o que ele só faz depois de um "sim" ("pergunta antes")
    ROTINA("rotina", "rotina executada", "rotinas executadas", false),
    COMPUTER_USE("computer_use", "tarefa no monitor", "tarefas no monitor", false),
    APAGAR_ARQUIVO("apagar_arquivo", "arquivo apagado", "arquivos apagados", false),
    DESLIGAR_PC("desligar_pc", "PC desligado", "vezes que desliguei o PC", false),
    ESQUECER_MEMORIA("esquecer_memoria", "memória esquecida", "memórias esquecidas", false),
    TAREFA_PLANILHA("tarefa_planilha", "tarefa de planilha agendada",
            "tarefas de planilha agendadas", false);

    private final String chave;
    private final String singular;
    private final String plural;
    private final boolean autonoma;

    TipoDeAcao(String chave, String singular, String plural, boolean autonoma) {
        this.chave = chave;
        this.singular = singular;
        this.plural = plural;
        this.autonoma = autonoma;
    }

    public String chave() {
        return chave;
    }

    /** Feita sem perguntar. É o filtro da seção "o que fiz sozinho" do briefing. */
    public boolean autonoma() {
        return autonoma;
    }

    /** O rótulo falado, no número certo: "1 arquivo criado", "3 arquivos criados". */
    public String rotulo(int quantidade) {
        return quantidade == 1 ? singular : plural;
    }

    /** O tipo daquela chave, ou {@code null} — chave desconhecida é linha a descartar, não erro. */
    public static TipoDeAcao porChave(String chave) {
        if (chave == null || chave.isBlank()) {
            return null;
        }
        String procurada = chave.trim();
        for (TipoDeAcao tipo : values()) {
            if (tipo.chave.equals(procurada)) {
                return tipo;
            }
        }
        return null;
    }
}
