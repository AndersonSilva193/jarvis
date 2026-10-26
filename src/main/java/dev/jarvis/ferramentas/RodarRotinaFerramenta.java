package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Seleciona apenas rotinas cadastradas no PC; o agente valida novamente antes de executar. */
@Component
public class RodarRotinaFerramenta implements Ferramenta {
    private final OperacoesPcService e2;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;
    public RodarRotinaFerramenta(OperacoesPcService e2, Aprovacoes aprovacoes, JsonMapper json) {
        this.e2 = e2; this.aprovacoes = aprovacoes; this.json = json;
    }
    @Override public String nome() { return "rodar_rotina"; }
    @Override public String descricao() {
        if (e2.rotinas().isEmpty()) return "Nenhuma rotina cadastrada no agente do PC.";
        StringBuilder texto = new StringBuilder("Executa apenas rotina cadastrada no PC. O agente decide se aceita. Rotinas: ");
        for (var r : e2.rotinas()) texto.append(r.nome()).append(" (" ).append(r.descricao())
                .append("; capacidades: ").append(String.join(", ", r.capacidades()))
                .append("). ");
        return texto.toString();
    }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        var campo = raiz.putObject("properties").putObject("nome");
        campo.put("type", "string").put("description", "Nome exato de uma rotina anunciada pelo PC.");
        var opcoes = campo.putArray("enum");
        if (e2.rotinas().isEmpty()) opcoes.add("nenhuma_rotina");
        e2.rotinas().forEach(r -> opcoes.add(r.nome()));
        raiz.putArray("required").add("nome");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) { return "Use a execução com contexto autenticado."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        String nome = Json.texto(entrada.path("nome"));
        var rotina = e2.rotinas().stream().filter(r -> r.nome().equals(nome)).findFirst();
        if (rotina.isEmpty()) return ResultadoFerramenta.texto("Rotina desconhecida ou agente E2 offline.");
        if (!e2.online()) return ResultadoFerramenta.texto("Agente do PC offline; rotina não iniciada.");
        String pedido = contexto.pedidoOriginal();
        if (pedido == null || pedido.isBlank() || pedido.length() > 8000)
            return ResultadoFerramenta.texto("Pedido original ausente ou acima do limite.");
        String pedidoConfirmado = pedido;
        try {
            String pergunta = "Vou rodar a rotina " + nome + " no seu PC; ela executa código na sua "
                    + "máquina e eu aviso quando terminar. Posso?";
            var confirmacao = aprovacoes.pedir("rotina", "Executar rotina " + nome,
                    pergunta, Aprovacoes.Risco.COMUM,
                    contexto.usuarioAutenticado(), contexto.idPedido(), () ->
                            iniciar(nome, pedidoConfirmado, contexto));
            return new ResultadoFerramenta(pergunta,
                    java.util.List.of(), java.util.List.of(),
                    confirmacao, java.util.List.of(), java.util.List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }

    /** O enfileiramento mora no {@code OperacoesPcService}: o monitor de sites usa o mesmo. */
    private String iniciar(String nome, String pedido, OuvinteProgresso contexto) {
        return e2.iniciarRotina(nome, pedido, contexto.canal(), contexto.usuarioAutenticado());
    }
}
