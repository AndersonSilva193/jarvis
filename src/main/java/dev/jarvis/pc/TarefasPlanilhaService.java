package dev.jarvis.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Autorizações persistentes para editar um XLSX inteiro em tarefas recorrentes. */
@Service
public class TarefasPlanilhaService {
    public record Tarefa(String id, long usuario, String arquivoId, String nomeArquivo,
            String instrucao, String cron, boolean substituirFormula, Instant proxima,
            boolean ativa) { }
    private final Map<String, Tarefa> tarefas = new LinkedHashMap<>();
    private final JsonMapper json;
    private final Path arquivo;
    private final ZoneId zona;
    private final Clock relogio;
    private final ObjectProvider<Assistente> assistente;
    private final TelegramClient telegram;

    @org.springframework.beans.factory.annotation.Autowired
    public TarefasPlanilhaService(JsonMapper json, JarvisProperties props,
            ObjectProvider<Assistente> assistente, TelegramClient telegram) {
        this(json, Path.of(props.pc().operacoesPasta()).resolveSibling("tarefas-planilha.json"),
                ZoneId.of(props.fusoHorario()), Clock.systemUTC(), assistente, telegram);
    }
    TarefasPlanilhaService(JsonMapper json, Path arquivo, ZoneId zona, Clock relogio,
            ObjectProvider<Assistente> assistente, TelegramClient telegram) {
        this.json = json; this.arquivo = arquivo; this.zona = zona; this.relogio = relogio;
        this.assistente = assistente; this.telegram = telegram;
        carregar();
    }

    public synchronized Tarefa registrar(long usuario, String arquivoId, String nomeArquivo,
            String instrucao, String cron, boolean substituirFormula) throws IOException {
        if (usuario <= 0 || !arquivoId.matches("[0-9a-fA-F-]{36}")
                || !nomeArquivo.toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")
                || instrucao.isBlank() || instrucao.length() > 2000)
            throw new IOException("tarefa de planilha inválida");
        CronExpression expressao;
        try { expressao = CronExpression.parse(cron); }
        catch (IllegalArgumentException e) { throw new IOException("agenda cron inválida", e); }
        Instant proxima = proxima(expressao, relogio.instant());
        Tarefa tarefa = new Tarefa(UUID.randomUUID().toString(), usuario, arquivoId,
                nomeArquivo, instrucao, cron, substituirFormula, proxima, true);
        tarefas.put(tarefa.id(), tarefa);
        try { salvar(); }
        catch (IOException e) { tarefas.remove(tarefa.id()); throw e; }
        return tarefa;
    }

    public synchronized boolean revogar(String id, long usuario) throws IOException {
        Tarefa tarefa = tarefas.get(id);
        if (tarefa == null || tarefa.usuario() != usuario) return false;
        tarefas.put(id, new Tarefa(tarefa.id(), tarefa.usuario(), tarefa.arquivoId(),
                tarefa.nomeArquivo(), tarefa.instrucao(), tarefa.cron(), tarefa.substituirFormula(),
                tarefa.proxima(), false));
        try { salvar(); }
        catch (IOException e) { tarefas.put(id, tarefa); throw e; }
        return true;
    }

    public synchronized java.util.List<Tarefa> listar(long usuario) {
        return tarefas.values().stream().filter(t -> t.usuario() == usuario).toList();
    }

    public synchronized boolean autorizada(String id, long usuario, String arquivoId,
            String nomeArquivo, boolean substituirFormula) {
        Tarefa tarefa = tarefas.get(id);
        return tarefa != null && tarefa.ativa() && tarefa.usuario() == usuario
                && tarefa.arquivoId().equals(arquivoId) && tarefa.nomeArquivo().equals(nomeArquivo)
                && (!substituirFormula || tarefa.substituirFormula());
    }

    private Instant proxima(CronExpression cron, Instant depois) throws IOException {
        ZonedDateTime data = cron.next(ZonedDateTime.ofInstant(depois, zona));
        if (data == null) throw new IOException("agenda sem próxima execução");
        return data.toInstant();
    }

    @Scheduled(fixedDelay = 60_000)
    public void executarPendentes() {
        java.util.List<Tarefa> prontas = new java.util.ArrayList<>();
        synchronized (this) {
            Instant agora = relogio.instant();
            for (Tarefa tarefa : java.util.List.copyOf(tarefas.values())) {
                if (!tarefa.ativa() || agora.isBefore(tarefa.proxima())) continue;
                try {
                    Instant seguinte = proxima(CronExpression.parse(tarefa.cron()), agora);
                    tarefas.put(tarefa.id(), new Tarefa(tarefa.id(), tarefa.usuario(), tarefa.arquivoId(),
                            tarefa.nomeArquivo(), tarefa.instrucao(), tarefa.cron(),
                            tarefa.substituirFormula(), seguinte, true));
                    salvar(); // Marca antes de iniciar: reinício não executa duas vezes.
                    prontas.add(tarefa);
                } catch (IOException | RuntimeException e) {
                    telegram.enviarMensagem(tarefa.usuario(), "Tarefa da planilha não iniciada: " + e.getMessage());
                }
            }
        }
        for (Tarefa tarefa : prontas) Thread.startVirtualThread(() -> executar(tarefa));
    }

    private void executar(Tarefa tarefa) {
        try {
            String pedidoId = "planilha-" + UUID.randomUUID();
            OuvinteProgresso contexto = new OuvinteProgresso() {
                @Override public String idPedido() { return pedidoId; }
                @Override public long usuarioAutenticado() { return tarefa.usuario(); }
                @Override public String idTarefaPlanilha() { return tarefa.id(); }
                @Override public String canal() { return "tarefa"; }
            };
            String resultado = assistente.getObject().executarTarefa(tarefa.instrucao()
                    + "\nArquivo autorizado: " + tarefa.nomeArquivo() + " (ID " + tarefa.arquivoId()
                    + "). Leia a versão atual e localize a célula antes de editar.", Canal.TAREFA, contexto);
            telegram.enviarMensagem(tarefa.usuario(), "Tarefa da planilha " + tarefa.nomeArquivo()
                    + ": " + resultado);
        } catch (Exception e) {
            telegram.enviarMensagem(tarefa.usuario(), "Tarefa da planilha " + tarefa.nomeArquivo()
                    + " falhou: " + e.getMessage());
        }
    }

    private synchronized void carregar() {
        if (!Files.isRegularFile(arquivo)) return;
        try {
            var raiz = json.readTree(Files.readString(arquivo));
            for (var item : raiz) {
                Tarefa t = new Tarefa(item.path("id").asText(), item.path("usuario").longValue(),
                        item.path("arquivoId").asText(), item.path("nomeArquivo").asText(),
                        item.path("instrucao").asText(), item.path("cron").asText(),
                        item.path("substituirFormula").asBoolean(false),
                        proxima(CronExpression.parse(item.path("cron").asText()), relogio.instant()),
                        item.path("ativa").asBoolean(false));
                tarefas.put(t.id(), t); // Execuções perdidas durante parada não são repetidas.
            }
        } catch (Exception e) { throw new IllegalStateException("tarefas de planilha corrompidas", e); }
    }

    private synchronized void salvar() throws IOException {
        Files.createDirectories(arquivo.getParent());
        ArrayNode lista = json.createArrayNode();
        for (Tarefa t : tarefas.values()) {
            ObjectNode item = lista.addObject();
            item.put("id", t.id()).put("usuario", t.usuario()).put("arquivoId", t.arquivoId())
                    .put("nomeArquivo", t.nomeArquivo()).put("instrucao", t.instrucao())
                    .put("cron", t.cron()).put("substituirFormula", t.substituirFormula())
                    .put("proxima", t.proxima().toString()).put("ativa", t.ativa());
        }
        Path temp = Files.createTempFile(arquivo.getParent(), ".tarefas-planilha-", ".tmp");
        try {
            Files.writeString(temp, json.writeValueAsString(lista));
            Files.move(temp, arquivo, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
}
