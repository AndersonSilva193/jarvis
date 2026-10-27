package dev.jarvis.ia;

/**
 * Qual das configurações de esforço vale para esta chamada ao modelo.
 *
 * <p><b>Não é a {@link dev.jarvis.custo.Origem}</b>, e a diferença é de pergunta: a origem responde
 * "quem gastou" e vai para o JSON do dia; o perfil responde "quanto pensar". As duas quase
 * coincidem — menos num ponto, que é justamente o que obriga a separá-las: a tarefa agendada e a
 * conversa gastam no mesmo balde ({@code Origem.CONVERSA}, como a própria {@code Origem}
 * documenta) e pensam diferente.
 *
 * <p>O {@code COMPUTER_USE} não tem perfil: ele monta o corpo do pedido por conta própria, fora do
 * {@link ClaudeClient}.
 *
 * <p>O padrão mora aqui, e não só no {@code application.yml}, pelo motivo do
 * {@code validadeDoHistorico()}: quem monta as properties à mão — dezenas de testes — tem de
 * receber o mesmo valor que o {@code .env} entrega.
 */
public enum PerfilDeEsforco {

    /** O laço de ferramentas: escolher ferramenta, ler resultado e decidir o passo seguinte. */
    CONVERSA("ESFORCO_CONVERSA", Esforco.MEDIO),

    /** Briefing falado: os dados chegam prontos e o canal proíbe mudar número ou fato. */
    BRIEFING("ESFORCO_BRIEFING", Esforco.BAIXO),

    /** A abertura da HUD: as fontes já foram reunidas, e quem bateu palma está esperando. */
    INICIALIZACAO("ESFORCO_INICIALIZACAO", Esforco.BAIXO),

    /** Classificador de resposta e anotador de memória: decisão curta, revalidada em Java depois. */
    APOIO("ESFORCO_APOIO", Esforco.BAIXO),

    /** Tarefa agendada: mesmo laço da conversa, ninguém lendo na hora. */
    TAREFA("ESFORCO_TAREFA", Esforco.MEDIO);

    private final String variavel;
    private final Esforco padrao;

    PerfilDeEsforco(String variavel, Esforco padrao) {
        this.variavel = variavel;
        this.padrao = padrao;
    }

    /** O nome no {@code .env}, que é o que o WARN de valor torto precisa dizer. */
    public String variavel() {
        return variavel;
    }

    public Esforco padrao() {
        return padrao;
    }
}
