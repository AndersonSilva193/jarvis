package dev.jarvis.web;

import java.util.List;
import dev.jarvis.conversa.Anexo;
import dev.jarvis.conversa.Confirmacao;
import dev.jarvis.conversa.RegistroConversas;
import dev.jarvis.conversa.RespostaConversa;

/** A camada HTTP é a única que conhece as rotas dos anexos. */
public final class ConversaDto {
    private ConversaDto() { }
    public record Resposta(String id, String canal, String texto, List<Anexo> anexos,
            Confirmacao confirmacao, String concluidoEm, String erro) { }
    public record Troca(String id, String canal, String pedido, Resposta resposta) { }
    public record Historico(String sessao, long revisao, List<Troca> trocas,
            RegistroConversas.Pedido emAndamento, Resposta ultima) { }
    public static Resposta de(RespostaConversa resposta) {
        if (resposta == null) return null;
        return new Resposta(resposta.id(), resposta.canal(), resposta.texto(), resposta.anexos().stream()
                .map(a -> new Anexo(a.id(), a.tipo(), a.nome(), a.mime(),
                        "link".equals(a.tipo()) ? a.url() : "/api/anexos/" + a.id(), a.expiraEm()))
                .toList(), resposta.confirmacao(), resposta.concluidoEm(), resposta.erro());
    }
    public static Historico de(RegistroConversas.Historico historico) {
        return new Historico(historico.sessao(), historico.revisao(), historico.trocas().stream()
                .map(t -> new Troca(t.id(), t.canal(), t.pedido(), de(t.resposta()))).toList(),
                historico.emAndamento(), de(historico.ultima()));
    }
}
