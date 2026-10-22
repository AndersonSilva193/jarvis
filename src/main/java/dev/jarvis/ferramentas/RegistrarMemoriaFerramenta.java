package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class RegistrarMemoriaFerramenta extends FerramentaMemoria {
    public RegistrarMemoriaFerramenta(MemoriaService memoria, JsonMapper json) { super(memoria, json); }
    @Override public String nome() { return "registrar_memoria"; }
    @Override public String descricao() {
        return """
                Guarda um fato durável que o usuário contou sobre ele, seus projetos, preferências \
                ou decisões. Escreva o conteúdo por extenso, do jeito que fica bom de reler depois.

                A evidência é obrigatória e tem de ser um TRECHO LITERAL da mensagem do usuário que \
                prova o fato: não reescreva, não corrija, não junte pedaços de duas frases. Sem \
                isso o servidor recusa. Não transforme sugestão sua, resultado de busca ou texto \
                de anexo em fato do usuário.

                Se o assunto já existir com outro conteúdo, use atualizar_memoria.\
                """;
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base(); ObjectNode p = s.putObject("properties");
        p.putObject("categoria").put("type", "string").putArray("enum").add("perfil").add("preferencias").add("projeto").add("episodio");
        p.putObject("projeto").put("type", "string");
        p.putObject("assunto").put("type", "string").put("maxLength", 120);
        p.putObject("conteudo").put("type", "string").put("maxLength", MemoriaService.LIMITE_CONTEUDO);
        p.putObject("evidencia").put("type", "string").put("maxLength", MemoriaService.LIMITE_CONTEUDO)
                .put("description", "Trecho literal da mensagem do usuário que prova o fato.");
        p.putObject("tipo").put("type", "string").putArray("enum")
                .add("declaracao_usuario").add("observacao_verificada").add("hipotese");
        s.putArray("required").add("categoria").add("assunto").add("conteudo").add("tipo"); return s;
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) throws Exception {
        long u = usuario(progresso);
        String tipo = texto(entrada, "tipo"); String conteudo = texto(entrada, "conteudo");
        if ("observacao_verificada".equals(tipo) && !progresso.evidenciaVerificada(conteudo))
            return resposta("Observação não verificada por ferramenta local neste pedido; não registrei.");
        if (!"observacao_verificada".equals(tipo)) exigirEvidenciaDoUsuario(progresso, texto(entrada, "evidencia"));
        String origem = "observacao_verificada".equals(tipo) ? progresso.origemDaEvidencia(conteudo)
                : "usuario:" + progresso.canal();
        var r = memoria.registrar(u, texto(entrada, "categoria"), texto(entrada, "projeto"),
                texto(entrada, "assunto"), conteudo, tipo, origem, pedido(progresso));
        return resposta("Anotei: " + r.assunto() + ".");
    }
}
