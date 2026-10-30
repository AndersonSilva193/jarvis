package dev.jarvis.conversa;

import java.util.List;

/**
 * A resposta de um turno, como a HUD e o histórico a veem.
 *
 * {@code texto} é o que se lê; {@code fala} é o que se ouve, quando os dois diferem. Hoje a
 * diferença é o aviso do anotador de memória ("Anotei: ..."), que precisa aparecer na tela para o
 * dono poder corrigir, mas não precisa ser narrado em voz alta no meio da conversa. Vazio ou nulo
 * significa "fale o texto", e é o caso de quase todas as respostas — ver {@link #paraFala()}.
 *
 * <p>{@code silenciosa} é a resposta que não se fala nem com o texto: o comando curto do holograma
 * ("explode", "mais") que a HUD confirma com um som, porque a cena mexendo já é a resposta.
 */
public record RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
        Confirmacao confirmacao, String concluidoEm, String erro, EscutaResposta escuta,
        String fala, boolean silenciosa) {
    public RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
            Confirmacao confirmacao, String concluidoEm, String erro, EscutaResposta escuta,
            String fala) {
        this(id, canal, texto, anexos, confirmacao, concluidoEm, erro, escuta, fala, false);
    }
    public RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
            Confirmacao confirmacao, String concluidoEm, String erro) {
        this(id, canal, texto, anexos, confirmacao, concluidoEm, erro, null, null);
    }
    public RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
            Confirmacao confirmacao, String concluidoEm, String erro, EscutaResposta escuta) {
        this(id, canal, texto, anexos, confirmacao, concluidoEm, erro, escuta, null);
    }
    public RespostaConversa {
        anexos = List.copyOf(anexos);
    }

    /** O que o Jarvis diz em voz alta. Sem fala própria, é o texto inteiro. */
    public String paraFala() {
        if (silenciosa) return "";
        return fala == null || fala.isBlank() ? texto : fala;
    }
}
