package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Corrige um registro que já existe. <b>Sem aprovação</b>, de propósito: a revisão anterior fica
 * guardada no {@code .historico}, então nada aqui é irreversível — e "isso mudou, agora é React"
 * não é pedido de autorização, é o dono mantendo a própria memória em ordem.
 */
@Component
public class AtualizarMemoriaFerramenta extends FerramentaMemoria {
    /** Pode ser nulo: os testes antigos montam a ferramenta sem o diário. */
    private final DiarioDeAcoes diario;

    public AtualizarMemoriaFerramenta(MemoriaService memoria, JsonMapper json) {
        this(memoria, json, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AtualizarMemoriaFerramenta(MemoriaService memoria, JsonMapper json,
            DiarioDeAcoes diario) {
        super(memoria, json);
        this.diario = diario;
    }

    @Override public String nome() { return "atualizar_memoria"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.MEMORIA; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return """
                Corrige um registro quando o usuário diz que algo mudou ou que você guardou errado \
                ("isso mudou, agora é React", "não é mais em Curitiba"). Não pede confirmação: a \
                versão anterior fica guardada.

                Use buscar_memoria antes para obter id e revisão. O conteúdo é o fato novo por \
                extenso; a evidência tem de ser um TRECHO LITERAL da mensagem do usuário, sem \
                reescrever nem juntar frases. Depois, diga ao usuário o que foi atualizado pelo \
                assunto — nunca pelo id.

                Contradição que você não entendeu direito pede esclarecimento, não um palpite \
                gravado.\
                """;
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base(); ObjectNode p = s.putObject("properties");
        p.putObject("id").put("type", "string"); p.putObject("revisao").put("type", "integer");
        p.putObject("conteudo").put("type", "string").put("maxLength", MemoriaService.LIMITE_CONTEUDO);
        p.putObject("evidencia").put("type", "string").put("maxLength", MemoriaService.LIMITE_CONTEUDO)
                .put("description", "Trecho literal da mensagem do usuário que prova a correção.");
        p.putObject("tipo").put("type", "string").putArray("enum")
                .add("declaracao_usuario").add("observacao_verificada").add("hipotese");
        s.putArray("required").add("id").add("revisao").add("conteudo").add("tipo"); return s;
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) throws Exception {
        long u = usuario(progresso);
        String tipo = texto(entrada, "tipo"); String conteudo = texto(entrada, "conteudo");
        if ("observacao_verificada".equals(tipo) && !progresso.evidenciaVerificada(conteudo))
            return resposta("Observação não verificada por ferramenta local neste pedido; não atualizei.");
        if (!"observacao_verificada".equals(tipo)) exigirEvidenciaDoUsuario(progresso, texto(entrada, "evidencia"));
        String origem = "observacao_verificada".equals(tipo) ? progresso.origemDaEvidencia(conteudo)
                : "usuario:" + progresso.canal();
        var r = memoria.atualizar(u, texto(entrada, "id"), revisao(entrada), conteudo,
                tipo, origem, pedido(progresso));
        // Corrigir memória é "faz e avisa", e o que foi corrigido sai de vista: a revisão antiga
        // vai para o .historico. O diário é o que deixa o dono reencontrar a mudança depois.
        if (diario != null)
            diario.registrarDoCanal(TipoDeAcao.ATUALIZAR_MEMORIA, "corrigi \"" + r.assunto() + "\"",
                    progresso.canal());
        return resposta("Atualizei: " + r.assunto() + ".");
    }
}
