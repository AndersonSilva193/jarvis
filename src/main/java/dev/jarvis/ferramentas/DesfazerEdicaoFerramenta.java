package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Restaura somente backup conhecido de edição original, por pedido explícito do usuário. */
@Component
public class DesfazerEdicaoFerramenta implements Ferramenta {
    private final FluxoEdicaoPc fluxo;
    private final JsonMapper json;
    public DesfazerEdicaoFerramenta(FluxoEdicaoPc fluxo, JsonMapper json) {
        this.fluxo = fluxo; this.json = json;
    }
    @Override public String nome() { return "desfazer_edicao"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.ARQUIVOS; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() { return "Restaura o backup de uma edição original "
            + "pelo editId; omita-o para desfazer a última edição original concluída neste servidor. "
            + "Exige pedido explícito do usuário. "
            + "Recusa arquivo alterado após a edição. Não aceita caminho de backup."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("editId").put("type", "string");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            OuvinteProgresso contexto) throws Exception {
        String solicitado = entrada == null ? "" : Json.texto(entrada.path("editId"));
        String editId = solicitado.isBlank()
                ? fluxo.ultimaEdicaoConcluida(contexto.usuarioAutenticado()).orElse("")
                : solicitado;
        if (contexto.usuarioAutenticado() <= 0 || contexto.idPedido() == null
                || !editId.matches("[0-9a-f-]{36}")
                || !contexto.pedidoOriginal().toLowerCase(java.util.Locale.ROOT)
                        .matches("(?s).*(desfa[cç]|desfazer|restaur|revert).*(edi[cç]|arquivo|altera[cç]|vers[aã]o).*"))
            return ResultadoFerramenta.texto("Desfazer requer pedido explícito e uma edição original "
                    + "concluída neste servidor. Para outra edição, informe o ID técnico.");
        // A preparação é o que confere que o backup existe e que o arquivo não mudou depois da
        // edição; só o editId atravessa, e o caminho do backup nunca sai do agente.
        ResultadoPc preparado;
        try { preparado = fluxo.preparar("preparar_desfazer",
                json.createObjectNode().put("editId", editId), contexto); }
        catch (IllegalStateException e) { return ResultadoFerramenta.texto(e.getMessage()); }
        if (!preparado.ok() || preparado.dados() == null)
            return ResultadoFerramenta.texto("Não preparei o desfazer: " + preparado.mensagem());
        return fluxo.aplicar("aplicar_desfazer", editId, contexto,
                nome -> "Voltei " + nome + " para a versão anterior.", false);
    }
}
