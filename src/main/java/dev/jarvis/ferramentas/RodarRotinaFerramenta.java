package dev.jarvis.ferramentas;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ComandoPc;
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
            var confirmacao = aprovacoes.pedir("rotina", "Executar rotina " + nome,
                    "Posso executar a rotina " + nome + " no seu PC?", Aprovacoes.Risco.COMUM,
                    contexto.usuarioAutenticado(), contexto.idPedido(), () ->
                            iniciar(nome, pedidoConfirmado, contexto));
            return new ResultadoFerramenta("Confirme em NOVA mensagem: confirmo. Para desistir: cancelar.",
                    java.util.List.of(), java.util.List.of(),
                    confirmacao, java.util.List.of(), java.util.List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return ResultadoFerramenta.texto(recusa.getMessage());
        }
    }

    private String iniciar(String nome, String pedido, OuvinteProgresso contexto) {
        if (!e2.online() || e2.rotinas().stream().noneMatch(r -> r.nome().equals(nome)))
            return "Agente offline ou rotina removida; nao iniciei.";
        ObjectNode dados = json.createObjectNode().put("pedido", pedido)
                .put("confirmado", true);
        ComandoPc comando = new ComandoPc(java.util.UUID.randomUUID().toString(),
                "rodar_rotina", nome, Instant.now().toString(), dados);
        try {
            var futuro = e2.enfileirar(comando, Duration.ofSeconds(60),
                    Duration.ofHours(1), contexto.canal(),
                    contexto.usuarioAutenticado());
            try {
                var resultado = futuro.get(30, TimeUnit.SECONDS);
                return "Operação " + comando.id() + ": "
                        + (resultado.ok() ? "concluída" : "falhou") + ". " + resultado.mensagem();
            } catch (java.util.concurrent.TimeoutException espera) {
                e2.pedirAviso(comando.id());
                return "Operação " + comando.id() + " em estado "
                        + e2.estado(comando.id()).orElseThrow().estado()
                        + ". Consulte status_operacao_pc pelo ID; o resultado final será avisado.";
            }
        } catch (InterruptedException interrompida) {
            e2.pedirAviso(comando.id());
            Thread.currentThread().interrupt();
            return "Operação " + comando.id() + " enviada; consulte o estado pelo ID.";
        } catch (Exception falha) {
            return "Não iniciei a rotina: " + falha.getMessage();
        }
    }
}
