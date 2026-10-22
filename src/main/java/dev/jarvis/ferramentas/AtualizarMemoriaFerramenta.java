package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AtualizarMemoriaFerramenta extends FerramentaMemoria {
    public AtualizarMemoriaFerramenta(MemoriaService memoria, JsonMapper json) { super(memoria, json); }
    @Override public String nome() { return "atualizar_memoria"; }
    @Override public String descricao() { return "Corrige memória existente só mediante pedido explícito do usuário. Para declaração ou hipótese, conteúdo deve ser trecho literal do pedido. Busque e leia primeiro para obter ID e revisão. Contradição sem correção clara exige esclarecimento."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base(); ObjectNode p = s.putObject("properties");
        p.putObject("id").put("type", "string"); p.putObject("revisao").put("type", "integer");
        p.putObject("conteudo").put("type", "string").put("maxLength", MemoriaService.LIMITE_CONTEUDO);
        p.putObject("tipo").put("type", "string").putArray("enum")
                .add("declaracao_usuario").add("observacao_verificada").add("hipotese");
        s.putArray("required").add("id").add("revisao").add("conteudo").add("tipo"); return s;
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) throws Exception {
        long u = usuario(progresso);
        String tipo = texto(entrada, "tipo"); String conteudo = texto(entrada, "conteudo");
        if ("observacao_verificada".equals(tipo) && !progresso.evidenciaVerificada(conteudo))
            return resposta("Observação não verificada por ferramenta local neste pedido; não atualizei.");
        if (!"observacao_verificada".equals(tipo)) exigirConteudoDoUsuario(progresso, conteudo);
        String origem = "observacao_verificada".equals(tipo) ? progresso.origemDaEvidencia(conteudo)
                : "usuario:" + progresso.canal();
        var r = memoria.atualizar(u, texto(entrada, "id"), revisao(entrada), conteudo,
                tipo, origem, pedido(progresso));
        return resposta("Memória atualizada: id=" + r.id() + " revisão=" + r.revisao());
    }
}
