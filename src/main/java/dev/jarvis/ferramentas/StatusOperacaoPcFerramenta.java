package dev.jarvis.ferramentas;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ArquivosPcService;
import dev.jarvis.pc.OperacoesPcService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class StatusOperacaoPcFerramenta implements Ferramenta {
    private final OperacoesPcService operacoes;
    private final ArquivosPcService arquivos;
    private final JsonMapper json;
    public StatusOperacaoPcFerramenta(OperacoesPcService operacoes, ArquivosPcService arquivos,
            JsonMapper json) {
        this.operacoes = operacoes; this.arquivos = arquivos; this.json = json;
    }
    @Override public String nome() { return "status_operacao_pc"; }
    @Override public String descricao() { return "Consulta pelo ID o estado real de um comando ou rotina do PC."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string");
        raiz.putArray("required").add("id"); return raiz;
    }
    @Override public String executar(JsonNode entrada) {
        String id = Json.texto(entrada.path("id"));
        if (!id.matches("[0-9a-fA-F-]{36}")) return "ID inválido.";
        return operacoes.estado(id).map(o -> "Operação " + id + ": " + o.estado()
                + (o.codigoSaida() == null ? "" : "; código " + o.codigoSaida())
                + ". " + o.mensagem()
                + operacoes.resultado(id).filter(r -> r.dados() != null)
                        .map(r -> "\n" + r.dados()).orElse(""))
                .orElse("Operação não encontrada ou fora da retenção.");
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            OuvinteProgresso progresso) {
        String id = Json.texto(entrada.path("id"));
        String texto = executar(entrada);
        if (!id.matches("[0-9a-fA-F-]{36}")) return ResultadoFerramenta.texto(texto);
        var resultado = operacoes.resultado(id).orElse(null);
        if (resultado == null) return ResultadoFerramenta.texto(texto);
        List<ResultadoFerramenta.Artefato> anexos = new ArrayList<>();
        if (resultado.temImagem() && Files.isRegularFile(operacoes.arquivoDeImagem(id)))
            anexos.add(new ResultadoFerramenta.Artefato(operacoes.arquivoDeImagem(id),
                    "tela-do-pc" + ("image/jpeg".equals(resultado.mime()) ? ".jpg" : ".png"),
                    resultado.mime()));
        if (resultado.arquivo()) arquivos.obter(id).ifPresent(caminho -> anexos.add(
                new ResultadoFerramenta.Artefato(caminho, resultado.mensagem(),
                        "application/octet-stream")));
        return new ResultadoFerramenta(texto, anexos, List.of(), null, List.of(), List.of());
    }
}
