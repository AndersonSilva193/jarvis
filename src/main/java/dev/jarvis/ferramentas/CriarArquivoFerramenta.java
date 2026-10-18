package dev.jarvis.ferramentas;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.ArquivosPcService;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.PedidoArquivo;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Cria um arquivo somente na pasta configurada do agente do PC. */
@Component
public class CriarArquivoFerramenta implements Ferramenta {
    private final PcService pc;
    private final ArquivosPcService arquivos;
    private final TelegramClient telegram;
    private final JsonMapper json;
    private final long chatId;

    public CriarArquivoFerramenta(PcService pc, ArquivosPcService arquivos, TelegramClient telegram,
            JsonMapper json, JarvisProperties props) {
        this.pc = pc; this.arquivos = arquivos; this.telegram = telegram; this.json = json;
        this.chatId = props.telegram() == null ? 0 : props.telegram().usuarioPermitido();
    }

    @Override public String nome() { return "criar_arquivo"; }
    @Override public String descricao() { return """
            Cria um arquivo novo na pasta Documentos/Jarvis do PC, e entrega anexo no Telegram e na HUD.
            Aceita md, txt, docx e pdf com conteúdo Markdown; xlsx e csv com abas, colunas e linhas
            estruturadas. Para informações atuais, faça busca web antes de redigir e informe fontes
            reais com título, URL HTTPS e data de consulta yyyy-MM-dd. Nunca invente referências.
            Não envie scripts, HTML, imagens, templates, macros ou URLs para carregar recursos.
            Para criar uma planilha nativa na conta Google use criar_planilha_google.
            """; }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode campos = raiz.putObject("properties");
        campos.putObject("nome").put("type", "string").put("maxLength", 120)
                .put("description", "Nome de arquivo, sem diretório nem extensão.");
        ObjectNode formato = campos.putObject("formato").put("type", "string");
        var opcoes = formato.putArray("enum");
        for (String f : List.of("md", "txt", "docx", "pdf", "xlsx", "csv")) opcoes.add(f);
        campos.putObject("conteudo").put("type", "string").put("maxLength", PedidoArquivo.LIMITE_MARKDOWN)
                .put("description", "Markdown para md, txt, docx ou pdf.");
        EsquemaArquivo.planilha(campos);
        campos.putObject("pesquisaAtual").put("type", "boolean");
        EsquemaArquivo.fontes(campos);
        raiz.putArray("required").add("nome").add("formato");
        return raiz;
    }

    @Override public String executar(JsonNode entrada) throws Exception {
        return executarDetalhado(entrada, new OuvinteProgresso() { }).texto();
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        String erro = PedidoArquivo.validar(entrada);
        if (!erro.isEmpty()) return ResultadoFerramenta.texto(erro);
        if (entrada.path("pesquisaAtual").asBoolean(false) && !progresso.pesquisaFeita())
            return ResultadoFerramenta.texto("Faça a busca web antes de criar documento sobre fatos atuais.");
        if (!pc.online()) return ResultadoFerramenta.texto("O agente do PC está offline; não criei o arquivo.");
        ComandoPc comando = new ComandoPc(java.util.UUID.randomUUID().toString(), "criar_arquivo", "",
                Instant.now().toString(), entrada.deepCopy());
        dev.jarvis.pc.ResultadoPc resultado;
        try {
            resultado = pc.enfileirar(comando, progresso.canal(), progresso.usuarioAutenticado())
                    .get(Duration.ofSeconds(150).toMillis(), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            if (pc.estadoE2(comando.id()).isPresent()) {
                pc.pedirAviso(comando.id());
                return ResultadoFerramenta.texto("Criação em andamento no PC: " + comando.id()
                        + ". Consulte status_operacao_pc; o resultado será avisado.");
            }
            pc.cancelar(comando.id());
            return ResultadoFerramenta.texto("O PC não confirmou a criação a tempo. Não publiquei anexo.");
        } catch (InterruptedException e) {
            if (pc.estadoE2(comando.id()).isPresent()) pc.pedirAviso(comando.id());
            else pc.cancelar(comando.id());
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!resultado.ok()) return ResultadoFerramenta.texto("Não criei o arquivo: " + resultado.mensagem());
        if (!resultado.arquivo()) return ResultadoFerramenta.texto("O PC não confirmou o arquivo gerado.");
        var caminho = arquivos.obter(comando.id());
        if (caminho.isEmpty()) return ResultadoFerramenta.texto("O arquivo não chegou ao servidor.");
        String formato = Json.texto(entrada.path("formato"));
        String nome = resultado.mensagem();
        if (nome.isBlank() || nome.contains("/") || nome.contains("\\") || !nome.endsWith("." + formato))
            nome = Json.texto(entrada.path("nome")) + "." + formato;
        String mime = switch (formato) {
            case "md" -> "text/markdown";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> "application/pdf";
        };
        String aviso = "";
        if (chatId > 0) {
            try { telegram.enviarDocumento(chatId, Files.readAllBytes(caminho.get()), nome); }
            catch (Exception e) { aviso = " Não consegui enviar pelo Telegram; o anexo está disponível na HUD."; }
        }
        return new ResultadoFerramenta("Arquivo criado: " + nome + "." + aviso, List.of(
                new ResultadoFerramenta.Artefato(caminho.get(), nome, mime)), List.of(), null,
                List.of(), List.of());
    }
}
