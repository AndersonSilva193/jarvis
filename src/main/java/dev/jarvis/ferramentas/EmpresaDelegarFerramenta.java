package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.EmpresaPcService;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Passa um pedido ao agente principal da empresa, que escolhe quem faz.
 *
 * <p>Os agentes têm terminal e arquivos no PC, então delegar passa pela aprovação como rodar rotina.
 */
@Component
public class EmpresaDelegarFerramenta implements Ferramenta {
    private static final int MAX_PEDIDO = 8000;
    private static final int MAX_NA_PERGUNTA = 240;

    private final EmpresaPcService empresa;
    private final OperacoesPcService e2;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;

    public EmpresaDelegarFerramenta(EmpresaPcService empresa, OperacoesPcService e2, Aprovacoes aprovacoes,
            JsonMapper json) {
        this.empresa = empresa;
        this.e2 = e2;
        this.aprovacoes = aprovacoes;
        this.json = json;
    }

    @Override public String nome() { return "empresa_delegar"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PC; }
    @Override public boolean simples() { return false; }
    @Override public boolean mudaEstado() { return true; }

    @Override public String descricao() {
        return "Passa um pedido à empresa do dono no Hermes3D. O agente principal decide quem faz e "
                + "pode criar agentes; o resultado chega por aviso quando terminar. Use quando ele pedir "
                + "algo para a empresa, para um time ou para um agente. Passe o pedido completo, nas "
                + "palavras dele. A ferramenta faz a pergunta de confirmação.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        esquema.putObject("properties").putObject("pedido").put("type", "string")
                .put("description", "O pedido completo para a empresa, com o contexto necessário.");
        esquema.putArray("required").add("pedido");
        return esquema;
    }

    @Override public String executar(JsonNode entrada) {
        return "Pedido autenticado necessario.";
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!e2.online()) return ResultadoFerramenta.pcOffline(EmpresaPcService.PC_OFFLINE);
        String pedido = Json.texto(entrada.path("pedido")).trim();
        if (pedido.isEmpty() || pedido.length() > MAX_PEDIDO) {
            return ResultadoFerramenta.texto("Pedido vazio ou acima de " + MAX_PEDIDO + " caracteres.");
        }
        String resumo = pedido.length() <= MAX_NA_PERGUNTA ? pedido : pedido.substring(0, MAX_NA_PERGUNTA) + "...";
        String canal = contexto.canal();
        long usuario = contexto.usuarioAutenticado();
        try {
            return ResultadoFerramenta.de(aprovacoes.encaminhar(AcaoAutonoma.EMPRESA_DELEGAR, null, "empresa_tarefa",
                    "Passar à empresa: " + resumo, "Vou passar para a empresa: " + resumo + ". Posso?",
                    Aprovacoes.Risco.COMUM, usuario, contexto.idPedido(), canal,
                    Aprovacoes.frase(() -> empresa.delegar(pedido, canal, usuario))));
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }
}
