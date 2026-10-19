package dev.jarvis.pc;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ferramentas.ResultadoFerramenta;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Usa o E2 para preparar e aplicar edições, sem aceitar caminhos enviados pelo modelo. */
@Service
public class FluxoEdicaoPc {
    private final OperacoesPcService operacoes;
    private final ArquivosPcService arquivos;
    private final TelegramClient telegram;
    private final JsonMapper json;

    public FluxoEdicaoPc(OperacoesPcService operacoes, ArquivosPcService arquivos,
            TelegramClient telegram, JsonMapper json) {
        this.operacoes = operacoes; this.arquivos = arquivos;
        this.telegram = telegram; this.json = json;
    }

    public ResultadoPc preparar(String acao, JsonNode dados, OuvinteProgresso contexto) throws Exception {
        if (!operacoes.online()) throw new IllegalStateException("Agente do PC offline.");
        ComandoPc comando = novo(acao, dados);
        try {
            return operacoes.enfileirar(comando, Duration.ofSeconds(30), Duration.ofMinutes(2),
                    contexto.canal(), contexto.usuarioAutenticado()).get(55, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new IllegalStateException("Preparação sem resposta; consulte status_operacao_pc: " + comando.id());
        }
    }

    public String aplicarDepoisDeConfirmar(String acao, String editId, String canal, long usuario) {
        if (!operacoes.online()) return "Agente do PC offline; edição não iniciada.";
        ComandoPc comando = novo(acao, json.createObjectNode().put("editId", editId));
        try {
            operacoes.enfileirar(comando, Duration.ofSeconds(30), Duration.ofMinutes(2), canal, usuario);
            operacoes.pedirAviso(comando.id());
            return "Edição autorizada. Operação " + comando.id()
                    + " iniciada; o resultado e o anexo serão avisados. Consulte status_operacao_pc pelo ID.";
        } catch (RuntimeException e) { return "Não iniciei a edição: " + e.getMessage(); }
    }

    public ResultadoFerramenta aplicarCopia(String editId, OuvinteProgresso contexto,
            String previa) throws Exception {
        ComandoPc comando = novo("aplicar_edicao", json.createObjectNode().put("editId", editId));
        ResultadoPc resultado;
        try {
            resultado = operacoes.enfileirar(comando, Duration.ofSeconds(30), Duration.ofMinutes(2),
                    contexto.canal(), contexto.usuarioAutenticado()).get(55, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            operacoes.pedirAviso(comando.id());
            return ResultadoFerramenta.texto(previa + "\nCriação da cópia em andamento: " + comando.id()
                    + ". Consulte status_operacao_pc; o resultado será avisado.");
        }
        if (!resultado.ok()) return ResultadoFerramenta.texto(previa + "\nCópia não criada: " + resultado.mensagem());
        if (!resultado.arquivo()) return ResultadoFerramenta.texto(previa + "\nCópia salva, entrega falhou.");
        var arquivo = arquivos.obter(comando.id());
        if (arquivo.isEmpty()) return ResultadoFerramenta.texto(previa + "\nCópia salva, entrega falhou.");
        String nome = resultado.mensagem();
        String aviso = "";
        if ("telegram".equals(contexto.canal()) && contexto.usuarioAutenticado() > 0)
            try { telegram.enviarDocumento(contexto.usuarioAutenticado(), Files.readAllBytes(arquivo.get()), nome); }
            catch (Exception e) { aviso = " Entrega pelo Telegram falhou; consulte o anexo na HUD."; }
        return new ResultadoFerramenta(previa + "\nCópia criada: " + nome + "." + aviso,
                List.of(new ResultadoFerramenta.Artefato(arquivo.get(), nome,
                        nome.endsWith(".md") ? "text/markdown" : "text/plain")),
                List.of(), null, List.of(), List.of());
    }

    private ComandoPc novo(String acao, JsonNode dados) {
        return new ComandoPc(UUID.randomUUID().toString(), acao, "", Instant.now().toString(),
                dados == null ? json.createObjectNode() : dados.deepCopy());
    }
}
