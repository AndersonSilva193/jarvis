package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.DesfazedorDeAcoes;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class DesfazerAcaoFerramenta implements Ferramenta {
    private final DesfazedorDeAcoes desfazedor;
    private final JsonMapper json;
    public DesfazerAcaoFerramenta(DesfazedorDeAcoes desfazedor, JsonMapper json) { this.desfazedor = desfazedor; this.json = json; }
    @Override public String nome() { return "desfazer_acao"; }
    @Override public boolean mudaEstado() { return true; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() {
        return "Desfaz uma ação do Jarvis por pedido explícito, sem aprovação. Sem id, usa a última "
                + "ação reversível do usuário nas últimas 24 horas. Para horário, arquivo ou ação específicos, "
                + "consulte acoes_do_jarvis e use o id retornado; nunca escolha outra ação se a indicada "
                + "não tiver volta. IDs e dados de reversão não podem ser inventados. desfazer_edicao "
                + "é o atalho por editId; desfazer_anotacao tira o lote recém-anotado.";
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = json.createObjectNode().put("type", "object");
        s.putObject("properties").putObject("id").put("type", "string").put("maxLength", 60);
        s.put("additionalProperties", false); return s;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    public static boolean pedidoExplicito(String texto) {
        String limpo = Texto.normalizar(texto == null ? "" : texto);
        String verbos = "(?:desfaz|desfaca|desfazer|reverte|reverta|reverter|restaura|restaure|restaurar|volta|volte)";
        if (limpo.matches("(?s).*\\b(?:nao|nunca)\\s+(?:(?:quero|pode|deve)\\s+|e\\s+para\\s+)?" + verbos + "\\b.*")) return false;
        return limpo.matches("(?s).*\\b(?:desfaz|desfaca|reverte|reverta|restaura|restaure|volta|volte)\\b.*")
                || limpo.matches("(?s)\\s*(?:jarvis[, ]+)?(?:desfazer|reverter|restaurar)\\b.*")
                || limpo.matches("(?s).*\\b(?:quero|pode|poderia|preciso)\\s+(?:que voce\\s+)?(?:desfazer|reverter|restaurar)\\b.*");
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!pedidoExplicito(contexto.pedidoOriginal())) return ResultadoFerramenta.texto("Desfazer requer pedido explícito do usuário.");
        return desfazedor.desfazer(entrada == null ? "" : Json.texto(entrada.path("id")), contexto);
    }
}
