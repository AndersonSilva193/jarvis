package dev.jarvis.conversa;

import java.util.List;

public record RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
        Confirmacao confirmacao, String concluidoEm, String erro, EscutaResposta escuta) {
    public RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
            Confirmacao confirmacao, String concluidoEm, String erro) {
        this(id, canal, texto, anexos, confirmacao, concluidoEm, erro, null);
    }
    public RespostaConversa {
        anexos = List.copyOf(anexos);
    }
}
