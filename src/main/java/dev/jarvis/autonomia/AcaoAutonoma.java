package dev.jarvis.autonomia;

import java.util.regex.Pattern;

/**
 * O catálogo <b>fechado</b> das ações que podem passar pelo {@code Aprovacoes}. Ação nova que pede
 * aprovação entra aqui de propósito, com padrão e piso escolhidos — nunca por omissão.
 *
 * <p>A {@link #chave()} é o que o dono escreve no {@code config/autonomia.md}, e por isso é estável:
 * renomear a constante Java não pode invalidar o arquivo de ninguém. O alvo, quando a ação aceita
 * um, vem depois de dois-pontos ({@code rotina.rodar:backup_notas}).
 *
 * <p>O <b>padrão</b> é o comportamento de antes do arquivo existir, e vale quando ele não diz nada.
 * O <b>piso</b> é o mínimo que nenhum arquivo afrouxa: desligar, reiniciar e computer use perguntam
 * sempre (o arquivo ainda pode proibi-los). Os dois de energia são {@code Risco.CRITICO}; o computer
 * use recebe um objetivo em texto livre escrito pelo modelo.
 *
 * <p>As frases ({@link #oQue()}) são o que a {@code minhas_permissoes} e o prompt falam — vão para
 * voz, então nada de nome de constante.
 */
public enum AcaoAutonoma {

    ROTINA_RODAR("rotina.rodar", true, Nivel.PERGUNTA, Nivel.FAZ_E_AVISA,
            "rodar rotina no PC"),
    COMPUTER_USE_INICIAR("computer_use.iniciar", false, Nivel.PERGUNTA, Nivel.PERGUNTA,
            "usar a tela do PC (computer use)"),
    PC_DESLIGAR("pc.desligar", false, Nivel.PERGUNTA, Nivel.PERGUNTA,
            "desligar o PC"),
    PC_REINICIAR("pc.reiniciar", false, Nivel.PERGUNTA, Nivel.PERGUNTA,
            "reiniciar o PC"),
    ARQUIVO_APAGAR_LOTE("arquivo.apagar_lote", false, Nivel.PERGUNTA, Nivel.FAZ_E_AVISA,
            "apagar mais de 5 arquivos no mesmo pedido"),
    PLANILHA_AGENDAR("planilha.agendar", false, Nivel.PERGUNTA, Nivel.FAZ_E_AVISA,
            "agendar edição recorrente de planilha"),
    MEMORIA_ESQUECER("memoria.esquecer", false, Nivel.PERGUNTA, Nivel.FAZ_E_AVISA,
            "esquecer uma lembrança"),
    EMPRESA_DELEGAR("empresa.delegar", false, Nivel.PERGUNTA, Nivel.FAZ_E_AVISA,
            "passar tarefa para a empresa no Hermes3D");

    /**
     * O alvo aceito. É a mesma régua que o {@code OperacoesPcService} aplica ao nome de rotina
     * anunciado pelo PC: um alvo fora dela nunca casaria com rotina nenhuma.
     */
    static final Pattern ALVO = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,31}");

    private final String chave;
    private final boolean aceitaAlvo;
    private final Nivel padrao;
    private final Nivel piso;
    private final String oQue;

    AcaoAutonoma(String chave, boolean aceitaAlvo, Nivel padrao, Nivel piso, String oQue) {
        this.chave = chave;
        this.aceitaAlvo = aceitaAlvo;
        this.padrao = padrao;
        this.piso = piso;
        this.oQue = oQue;
    }

    public String chave() { return chave; }
    public boolean aceitaAlvo() { return aceitaAlvo; }
    public Nivel padrao() { return padrao; }
    public Nivel piso() { return piso; }

    /** A frase da ação inteira ("rodar rotina no PC"). */
    public String oQue() { return oQue; }

    /** A frase de um alvo só ("rodar a rotina backup_notas"). Só faz sentido em {@link #aceitaAlvo()}. */
    public String oQue(String alvo) {
        return alvo == null || alvo.isEmpty() ? oQue : "rodar a rotina " + alvo;
    }

    /** A ação daquela chave, ou {@code null} — chave desconhecida é linha a ignorar, não erro. */
    public static AcaoAutonoma porChave(String chave) {
        for (AcaoAutonoma acao : values()) {
            if (acao.chave.equals(chave)) {
                return acao;
            }
        }
        return null;
    }

    static boolean alvoValido(String alvo) {
        return alvo != null && ALVO.matcher(alvo).matches();
    }
}
