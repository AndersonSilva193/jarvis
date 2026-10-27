package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CancelarOperacaoPcFerramenta implements Ferramenta {
    private final OperacoesPcService operacoes;
    private final JsonMapper json;
    public CancelarOperacaoPcFerramenta(OperacoesPcService operacoes, JsonMapper json) {
        this.operacoes = operacoes; this.json = json;
    }
    @Override public String nome() { return "cancelar_operacao_pc"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PC; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Solicita cancelamento pelo ID. Antes do início impede execução; durante a execução depende do agente receber e encerrar a árvore de processos.";
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string");
        raiz.putArray("required").add("id"); return raiz;
    }
    @Override public String executar(JsonNode entrada) {
        String id = Json.texto(entrada.path("id"));
        if (!id.matches("[0-9a-fA-F-]{36}")) return "ID inválido.";
        return operacoes.cancelar(id) ? "Cancelamento solicitado para " + id
                + ". Consulte o estado; não é instantâneo." : "Operação não encontrada ou já encerrada.";
    }
}
