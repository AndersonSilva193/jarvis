package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.autonomia.Concessao;
import dev.jarvis.autonomia.Concessoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * "Volta a perguntar antes de rodar rotina", "cancela as permissões que te dei": tira concessões.
 *
 * <p>Sem aprovação e sem prova de citação, de propósito: reduzir autonomia é sempre seguro. O pior
 * que uma chamada indevida faz é o Jarvis voltar a perguntar. Cada revogação entra no diário, e
 * revogar também cala a oferta "quer que eu pare de perguntar?" por 30 dias.
 */
@Component
public class RevogarPermissaoFerramenta implements Ferramenta {

    private final Concessoes concessoes;
    private final JsonMapper json;

    public RevogarPermissaoFerramenta(Concessoes concessoes, JsonMapper json) {
        this.concessoes = concessoes;
        this.json = json;
    }

    @Override public String nome() { return "revogar_permissao"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Cancela permissões que o usuário deu para você agir sem perguntar ('volta a "
                + "perguntar', 'cancela as permissões que te dei'). Sem chave, cancela todas. Não "
                + "pede confirmação.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode props = raiz.putObject("properties");
        var opcoes = props.putObject("chave").put("type", "string")
                .put("description", "A ação; omita para cancelar todas.").putArray("enum");
        for (AcaoAutonoma acao : AcaoAutonoma.values()) opcoes.add(acao.chave());
        props.putObject("alvo").put("type", "string").put("maxLength", 32)
                .put("description", "Só para rotina.rodar: o nome da rotina.");
        return raiz;
    }

    @Override
    public String executar(JsonNode entrada) {
        return revogar(entrada, 0, "");
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        return ResultadoFerramenta.texto(revogar(entrada, contexto.usuarioAutenticado(), contexto.canal()));
    }

    private String revogar(JsonNode entrada, long usuario, String canal) {
        String chave = Json.texto(entrada.path("chave")).strip();
        AcaoAutonoma acao = chave.isEmpty() ? null : AcaoAutonoma.porChave(chave);
        if (!chave.isEmpty() && acao == null) return "Essa ação não está no meu catálogo.";
        String alvo = Json.texto(entrada.path("alvo")).strip();
        List<Concessao> saiu = concessoes.revogar(acao, alvo.isEmpty() ? null : alvo, usuario, canal);
        if (saiu.isEmpty()) {
            return acao == null ? "Você não tinha me dado nenhuma permissão; eu já pergunto."
                    : "Não havia permissão para " + acao.oQue(alvo.isEmpty() ? null : alvo)
                            + "; eu já pergunto antes.";
        }
        List<String> oQue = new ArrayList<>();
        for (Concessao c : saiu) if (!oQue.contains(c.oQue())) oQue.add(c.oQue());
        return "Pronto: volto a perguntar antes de " + String.join(", ", oQue) + ".";
    }

    @Override
    public String progresso() {
        return "cancelando a permissão";
    }
}
