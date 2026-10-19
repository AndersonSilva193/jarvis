package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.pc.PcService;
import dev.jarvis.conversa.OuvinteProgresso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Localiza documentos cadastrados na pasta do agente Windows. */
@Component
public class ListarArquivosFerramenta implements Ferramenta {
    private final PcService pc;
    private final JsonMapper json;
    public ListarArquivosFerramenta(PcService pc, JsonMapper json) { this.pc = pc; this.json = json; }
    @Override public String nome() { return "listar_arquivos"; }
    @Override public String descricao() { return "Localiza arquivos locais do Jarvis por nome e formato, com paginação. Devolve IDs permanentes, metadados e estado. Se houver nomes semelhantes, apresente as opções ao usuário antes de ler; não escolha silenciosamente. Não consulta Google Sheets nativo."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode p = raiz.putObject("properties");
        p.putObject("nome").put("type", "string").put("maxLength", 120);
        var f = p.putObject("formato").put("type", "string");
        var opcoes = f.putArray("enum");
        for (String tipo : new String[]{"txt", "md", "json", "docx", "pdf", "xlsx", "csv"}) opcoes.add(tipo);
        p.putObject("pagina").put("type", "integer").put("minimum", 1).put("maximum", 10000);
        p.putObject("tamanhoPagina").put("type", "integer").put("minimum", 1).put("maximum", 50);
        return raiz;
    }
    @Override public String executar(JsonNode entrada) throws Exception {
        return ConsultaArquivosPc.executar(pc, nome(), entrada);
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            OuvinteProgresso progresso) throws Exception {
        return ResultadoFerramenta.texto(ConsultaArquivosPc.executar(pc, nome(), entrada, progresso));
    }
}
