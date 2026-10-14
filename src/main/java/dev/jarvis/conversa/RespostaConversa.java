package dev.jarvis.conversa;

import java.util.List;

public record RespostaConversa(String id, String canal, String texto, List<Anexo> anexos,
        Confirmacao confirmacao, String concluidoEm, String erro) {
    public RespostaConversa {
        anexos = List.copyOf(anexos);
    }
}
