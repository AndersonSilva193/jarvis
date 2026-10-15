package dev.jarvis.ia;

/**
 * De onde veio o pedido. O que muda é o <b>formato</b> da resposta: o Telegram é lido na tela e
 * aceita formatação, a HUD e o briefing são falados em voz alta, e a tarefa agendada roda sem
 * ninguém lendo na hora.
 */
public enum Canal {

    TELEGRAM("""
            Você está no Telegram, lido no celular. Pode usar negrito (**assim**), listas curtas
            começando com "- " e blocos de código com ``` — sempre com moderação, como pede a
            personalidade. Mensagens curtas e escaneáveis."""),

    HUD("""
            Você está na tela HUD e o seu texto será falado em voz alta. Escreva frases curtas e
            naturais de ouvir: nada de markdown, listas, emoji ou link escrito por extenso.
            Referências e arquivos são apresentados em anexos pela tela quando fornecidos pelas
            ferramentas. Não invente anexos, códigos de confirmação nem capacidades ausentes.
            Quando você abrir ou comandar algo na tela, fale pouco: "Aqui está, senhor.",
            "Tela limpa.", "Pronto." O card já mostra os dados — não leia em voz alta o que ele
            mostra, nem descreva o que o usuário está vendo."""),

    BRIEFING("""
            Este é o briefing do dia e o seu texto será falado em voz alta. Frases curtas e naturais
            de ouvir: nada de markdown, listas, emoji ou link escrito por extenso. Os dados já vêm
            prontos — não altere nenhum número, nome ou fato, e não acrescente nada que não recebeu."""),

    TAREFA("""
            Esta é uma tarefa agendada: ninguém está lendo na hora. Comece pelo resultado, com uma
            linha de status (concluído, em andamento ou falhou, com o motivo). Sem markdown.""");

    private final String regrasDeFormato;

    Canal(String regrasDeFormato) {
        this.regrasDeFormato = regrasDeFormato;
    }

    /** Nome como ele aparece no prompt: telegram, hud, briefing, tarefa. */
    public String nome() {
        return name().toLowerCase();
    }

    /** As regras de escrita deste canal, que entram no bloco dinâmico do system. */
    public String regrasDeFormato() {
        return regrasDeFormato;
    }
}
