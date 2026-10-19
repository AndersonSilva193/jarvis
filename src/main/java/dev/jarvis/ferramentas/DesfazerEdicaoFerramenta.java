package dev.jarvis.ferramentas;

import java.util.List;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.IntencoesPc;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Restaura somente backup conhecido de edição original, após confirmação autenticada. */
@Component
public class DesfazerEdicaoFerramenta implements Ferramenta {
    private final FluxoEdicaoPc fluxo;
    private final IntencoesPc intencoes;
    private final JsonMapper json;
    public DesfazerEdicaoFerramenta(FluxoEdicaoPc fluxo, IntencoesPc intencoes, JsonMapper json) {
        this.fluxo = fluxo; this.intencoes = intencoes; this.json = json;
    }
    @Override public String nome() { return "desfazer_edicao"; }
    @Override public String descricao() { return "Prepara a restauração do backup de uma edição original "
            + "pelo editId. Exige pedido explícito do usuário e nova confirmação autenticada. "
            + "Recusa arquivo alterado após a edição. Não aceita caminho de backup."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("editId").put("type", "string");
        raiz.putArray("required").add("editId"); return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            OuvinteProgresso contexto) throws Exception {
        String editId = Json.texto(entrada.path("editId"));
        if (contexto.usuarioAutenticado() <= 0 || contexto.idPedido() == null
                || !editId.matches("[0-9a-f-]{36}")
                || !contexto.pedidoOriginal().toLowerCase(java.util.Locale.ROOT)
                        .matches("(?s).*(desfa[cç]|desfazer|restaur|revert).*(edi[cç]|arquivo|altera[cç]|vers[aã]o).*"))
            return ResultadoFerramenta.texto("Desfazer requer editId e pedido explícito autenticado.");
        ResultadoPc preparado;
        try { preparado = fluxo.preparar("preparar_desfazer",
                json.createObjectNode().put("editId", editId), contexto); }
        catch (IllegalStateException e) { return ResultadoFerramenta.texto(e.getMessage()); }
        if (!preparado.ok() || preparado.dados() == null)
            return ResultadoFerramenta.texto("Não preparei o desfazer: " + preparado.mensagem());
        String nome = Json.texto(preparado.dados().path("nome"));
        String canal = contexto.canal(); long usuario = contexto.usuarioAutenticado();
        try {
            var confirmacao = intencoes.pedir("desfazer_edicao", "Restaurar " + nome
                    + " pelo backup " + editId, usuario, contexto.idPedido(), () ->
                    fluxo.aplicarDepoisDeConfirmar("aplicar_desfazer", editId, canal, usuario));
            return new ResultadoFerramenta("Desfazer preparado para " + nome + ". Hash atual: "
                    + Json.texto(preparado.dados().path("hashAtual")) + "; restaurado: "
                    + Json.texto(preparado.dados().path("hashRestaurado"))
                    + ". Prévia:\n" + Json.texto(preparado.dados().path("previa"))
                    + (preparado.dados().path("previaTruncada").asBoolean(false)
                            ? "\nPrévia truncada." : "")
                    + ". Confirme em NOVA mensagem: confirmo.",
                    List.of(), List.of(), confirmacao, List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultadoFerramenta.texto("Confirmação indisponível: " + e.getMessage());
        }
    }
}
