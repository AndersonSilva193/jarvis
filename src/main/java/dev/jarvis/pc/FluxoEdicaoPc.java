package dev.jarvis.pc;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private record UltimaEdicao(String editId, String comandoId) { }
    private final ConcurrentHashMap<Long, UltimaEdicao> ultimasEdicoes = new ConcurrentHashMap<>();

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
            throw new IllegalStateException("Preparação sem resposta; consulte o status da operação no PC.");
        }
    }

    public String aplicarDepoisDeConfirmar(String acao, String editId, String canal, long usuario) {
        if (!operacoes.online()) return "Agente do PC offline; edição não iniciada.";
        ComandoPc comando = novo(acao, json.createObjectNode().put("editId", editId));
        try {
            operacoes.enfileirar(comando, Duration.ofSeconds(30), Duration.ofMinutes(2), canal, usuario);
            operacoes.pedirAviso(comando.id());
            if ("aplicar_edicao".equals(acao))
                ultimasEdicoes.put(usuario, new UltimaEdicao(editId, comando.id()));
            return "Edição autorizada. Avisarei o resultado e entregarei o arquivo.";
        } catch (RuntimeException e) { return "Não iniciei a edição: " + e.getMessage(); }
    }

    public Optional<String> ultimaEdicaoConcluida(long usuario) {
        UltimaEdicao ultima = ultimasEdicoes.get(usuario);
        if (ultima == null) return Optional.empty();
        var estado = operacoes.estado(ultima.comandoId()).orElse(null);
        return estado != null && "concluído".equals(estado.estado())
                ? Optional.of(ultima.editId()) : Optional.empty();
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
            return ResultadoFerramenta.texto(previa
                    + "\nCriação da cópia em andamento; avisarei o resultado.");
        }
        if (!resultado.ok()) return ResultadoFerramenta.texto(previa + "\nCópia não criada: " + resultado.mensagem());
        if (!resultado.arquivo()) {
            String nome = resultado.mensagem().replaceFirst("^salvo, entrega falhou: ", "");
            return ResultadoFerramenta.texto(previa + "\nCópia criada: " + nome
                    + "; salvo, entrega falhou.");
        }
        var arquivo = arquivos.obter(comando.id());
        if (arquivo.isEmpty()) return ResultadoFerramenta.texto(previa + "\nCópia criada: "
                + resultado.mensagem() + "; salvo, entrega falhou.");
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
