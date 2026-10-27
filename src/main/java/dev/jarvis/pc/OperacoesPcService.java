package dev.jarvis.pc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Diário persistente do protocolo E2. A posse de uma entrega não é prova de execução. */
@Service
public class OperacoesPcService {
    public static final int VERSAO = 2;
    private static final int LIMITE_FILA = 100;
    private static final int LIMITE_REGISTROS = 500;
    private static final Duration POSSE = Duration.ofSeconds(45);
    private static final Duration RETENCAO = Duration.ofDays(2);

    public record Retrato(String id, String acao, String estado, String criadoEm,
            String prazoInicio, String prazoExecucao, String mensagem, Integer codigoSaida,
            boolean cancelar) { }
    public record Entrega(ComandoPc comando, String prazoInicio, String prazoExecucao) { }
    public record RotinaPublica(String nome, String descricao, List<String> capacidades) { }

    private static final class Operacao {
        ComandoPc comando;
        Instant inicio;
        Instant execucao;
        Instant posse;
        Instant atualizado;
        String estado = "pendente";
        String mensagem = "";
        Integer codigoSaida;
        ResultadoPc resultado;
        String assinatura = "";
        boolean cancelar;
        boolean avisoTentado;
        boolean avisoPendente;
        String canal = "";
        long chatId;
        final CompletableFuture<ResultadoPc> futuro = new CompletableFuture<>();
        Operacao(ComandoPc comando, Instant inicio, Instant execucao, Instant agora) {
            this.comando = comando; this.inicio = inicio; this.execucao = execucao; this.atualizado = agora;
        }
        boolean finalizada() {
            return List.of("concluído", "falhou", "cancelado", "expirado", "resultado_incerto")
                    .contains(estado);
        }
        Retrato retrato() {
            return new Retrato(comando.id(), comando.acao(), estado, comando.criadoEm(),
                    inicio.toString(), execucao.toString(), mensagem, codigoSaida, cancelar);
        }
    }

    private final Map<String, Operacao> operacoes = new LinkedHashMap<>();
    private final JsonMapper json;
    private final Path arquivo;
    private final Clock relogio;
    private volatile Instant ultimoContato;
    /**
     * As rotinas anunciadas moram no {@link CatalogoDoPc}, que as guarda em disco.
     *
     * <p>Elas vão para o {@code esquemaEntrada()} da {@code rodar_rotina}, isto é, para o array
     * {@code tools} — e uma lista que esvazia a cada restart reconstruía o prefixo do cache de
     * prompt inteiro. Quem lê não muda de lugar: continua o {@link #rotinas()} daqui.
     */
    private final CatalogoDoPc catalogo;

    @Autowired
    public OperacoesPcService(JsonMapper json, JarvisProperties props, CatalogoDoPc catalogo) {
        this(json, Path.of(props.pc().operacoesPasta() == null || props.pc().operacoesPasta().isBlank()
                ? "/data/pc/operacoes.json" : props.pc().operacoesPasta()), Clock.systemUTC(),
                catalogo);
    }
    public OperacoesPcService(JsonMapper json, Path arquivo, Clock relogio) {
        this(json, arquivo, relogio, CatalogoDoPc.emMemoria());
    }
    public OperacoesPcService(JsonMapper json, Path arquivo, Clock relogio, CatalogoDoPc catalogo) {
        this.json = json; this.arquivo = arquivo; this.relogio = relogio;
        this.catalogo = catalogo == null ? CatalogoDoPc.emMemoria() : catalogo;
        carregar();
    }

    public synchronized CompletableFuture<ResultadoPc> enfileirar(ComandoPc comando,
            Duration prazoInicio, Duration prazoExecucao, String canal, long chatId) {
        Operacao anterior = operacoes.get(comando.id());
        if (anterior != null) return anterior.futuro;
        long ativos = operacoes.values().stream().filter(o -> !o.finalizada()).count();
        if (ativos >= LIMITE_FILA) throw new IllegalStateException("fila do PC cheia");
        while (operacoes.size() >= LIMITE_REGISTROS) {
            String antigo = operacoes.values().stream().filter(o -> o.finalizada()
                            && (!o.avisoPendente || o.avisoTentado))
                    .map(o -> o.comando.id()).findFirst().orElse(null);
            if (antigo == null) throw new IllegalStateException("limite do diário E2 atingido");
            operacoes.remove(antigo);
        }
        Instant agora = relogio.instant();
        Operacao nova = new Operacao(comando, agora.plus(prazoInicio),
                agora.plus(prazoInicio).plus(prazoExecucao), agora);
        nova.canal = canal == null ? "" : canal; nova.chatId = chatId;
        operacoes.put(comando.id(), nova);
        try { salvar(); }
        catch (RuntimeException falha) { operacoes.remove(comando.id()); throw falha; }
        notifyAll();
        return nova.futuro;
    }

    public Optional<Entrega> proximo(Duration espera) throws InterruptedException {
        long fim = System.nanoTime() + espera.toNanos();
        synchronized (this) {
            ultimoContato = relogio.instant();
            do {
                Instant agora = relogio.instant();
                for (Operacao o : operacoes.values()) {
                    if (o.finalizada() || o.cancelar || !agora.isBefore(o.inicio)) continue;
                    if (o.estado.equals("pendente") ||
                            (o.estado.equals("entregue") && !agora.isBefore(o.posse))) {
                        String anterior = o.estado;
                        Instant posseAnterior = o.posse, atualizadoAnterior = o.atualizado;
                        o.estado = "entregue"; o.posse = agora.plus(POSSE); o.atualizado = agora;
                        try { salvar(); }
                        catch (RuntimeException falha) {
                            o.estado = anterior; o.posse = posseAnterior;
                            o.atualizado = atualizadoAnterior; throw falha;
                        }
                        return Optional.of(new Entrega(o.comando, o.inicio.toString(), o.execucao.toString()));
                    }
                }
                long restante = fim - System.nanoTime();
                if (restante <= 0) return Optional.empty();
                wait(Math.max(1, Math.min(Duration.ofNanos(restante).toMillis(), 1000)));
            } while (true);
        }
    }

    public synchronized Optional<Retrato> estado(String id) {
        Operacao o = operacoes.get(id);
        return o == null ? Optional.empty() : Optional.of(o.retrato());
    }
    public synchronized Optional<ResultadoPc> resultado(String id) {
        Operacao o = operacoes.get(id);
        return o == null ? Optional.empty() : Optional.ofNullable(o.resultado);
    }
    /** A captura de computer use vive somente ate a rodada seguinte do modelo. */
    public synchronized void descartarImagemComputer(String id) {
        Operacao o = operacoes.get(id);
        if (o == null || !"computer_acao".equals(o.comando.acao()) || o.resultado == null) return;
        ResultadoPc r = o.resultado;
        o.resultado = new ResultadoPc(r.comandoId(), r.ok(), r.mensagem(), null, null,
                r.arquivo(), r.dados());
        try { Files.deleteIfExists(caminhoImagem(id)); }
        catch (IOException ignorada) { }
    }
    public Path arquivoDeUpload(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("ID inválido");
        return arquivo.getParent().resolve("arquivos").resolve(id + ".bin");
    }
    public Path arquivoDeImagem(String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("ID inválido");
        return caminhoImagem(id);
    }
    public synchronized boolean recebido(String id) { return avancar(id, "recebido"); }
    public synchronized boolean executando(String id) { return avancar(id, "executando"); }
    private boolean avancar(String id, String novo) {
        Operacao o = operacoes.get(id);
        if (o == null) return false;
        if (o.estado.equals(novo)) return true;
        if (o.finalizada() || o.cancelar || !relogio.instant().isBefore(o.inicio)
                && novo.equals("recebido")) return false;
        if (novo.equals("recebido") && !o.estado.equals("entregue")) return false;
        if (novo.equals("executando") && !o.estado.equals("recebido")) return false;
        String anterior = o.estado; Instant atualizadoAnterior = o.atualizado;
        o.estado = novo; o.atualizado = relogio.instant();
        try { salvar(); }
        catch (RuntimeException falha) {
            o.estado = anterior; o.atualizado = atualizadoAnterior; throw falha;
        }
        return true;
    }

    public synchronized boolean concluir(String id, String estado, ResultadoPc resultado,
            Integer codigoSaida) {
        return concluir(id, estado, resultado, codigoSaida, resultado.mensagem());
    }
    public synchronized boolean concluir(String id, String estado, ResultadoPc resultado,
            Integer codigoSaida, String mensagemCompleta) {
        Operacao o = operacoes.get(id);
        if (o == null || !List.of("concluído", "falhou", "cancelado", "resultado_incerto")
                .contains(estado)) return false;
        String assinatura = assinar(estado, resultado, codigoSaida, mensagemCompleta);
        if (o.finalizada()) {
            if (o.estado.equals("resultado_incerto") && !estado.equals("resultado_incerto")) {
                // Uma observação posterior pode resolver a incerteza; o aviso não é repetido.
            } else return o.estado.equals(estado) && o.assinatura.equals(assinatura);
        }
        if (!List.of("recebido", "executando", "entregue", "resultado_incerto")
                .contains(o.estado)) return false;
        String estadoAnterior = o.estado, mensagemAnterior = o.mensagem,
                assinaturaAnterior = o.assinatura;
        Integer codigoAnterior = o.codigoSaida;
        ResultadoPc resultadoAnterior = o.resultado;
        Instant atualizadoAnterior = o.atualizado;
        o.estado = estado;
        o.mensagem = mensagemCompleta.replaceAll("[\\r\\n\\p{Cntrl}]", " ").trim();
        o.codigoSaida = codigoSaida;
        o.resultado = resultado;
        o.assinatura = assinatura;
        if (resultado.temImagem()) guardarImagem(id, resultado.imagem());
        o.atualizado = relogio.instant();
        try { salvar(); }
        catch (RuntimeException falha) {
            o.estado = estadoAnterior; o.mensagem = mensagemAnterior;
            o.assinatura = assinaturaAnterior; o.codigoSaida = codigoAnterior;
            o.resultado = resultadoAnterior; o.atualizado = atualizadoAnterior;
            throw falha;
        }
        o.futuro.complete(resultado); notifyAll();
        return true;
    }
    public synchronized boolean cancelar(String id) {
        Operacao o = operacoes.get(id);
        if (o == null || o.finalizada()) return false;
        if (o.estado.equals("pendente") || o.estado.equals("entregue")) {
            finalizar(o, "cancelado", "cancelado antes do início");
        } else { o.cancelar = true; o.atualizado = relogio.instant(); salvar(); }
        notifyAll(); return true;
    }
    public synchronized boolean cancelamentoPedido(String id) {
        ultimoContato = relogio.instant();
        Operacao o = operacoes.get(id);
        return o != null && o.cancelar;
    }
    public synchronized boolean marcarAviso(String id) {
        Operacao o = operacoes.get(id);
        if (o == null || !o.finalizada() || !o.avisoPendente || o.avisoTentado || o.canal.isBlank()) return false;
        o.avisoTentado = true;
        try { salvar(); }
        catch (RuntimeException falha) { o.avisoTentado = false; throw falha; }
        return true;
    }
    public synchronized void pedirAviso(String id) {
        Operacao o = operacoes.get(id);
        if (o != null) { o.avisoPendente = true; salvar(); }
    }
    public synchronized List<String> avisosPendentes() {
        return operacoes.values().stream().filter(o -> o.avisoPendente && o.finalizada()
                && !o.avisoTentado).map(o -> o.comando.id()).toList();
    }
    public synchronized String canal(String id) {
        Operacao o = operacoes.get(id); return o == null ? "" : o.canal;
    }
    public synchronized long chatId(String id) {
        Operacao o = operacoes.get(id); return o == null ? 0 : o.chatId;
    }
    public boolean online() {
        Instant visto = ultimoContato;
        return visto != null && Duration.between(visto, relogio.instant()).compareTo(Duration.ofSeconds(90)) < 0;
    }
    public synchronized void catalogo(List<RotinaPublica> anuncios) {
        ultimoContato = relogio.instant();
        catalogo.anunciarRotinas(anuncios == null ? List.of() : anuncios.stream().filter(r ->
                r.nome() != null && r.nome().matches("[a-z0-9][a-z0-9_.-]{0,31}")
                && r.descricao() != null && !r.descricao().isBlank()
                && r.descricao().length() <= 160
                && !r.descricao().matches(".*[\\r\\n\\p{Cntrl}].*")
                && r.capacidades() != null && r.capacidades().size() <= 8
                && r.capacidades().stream().allMatch(c -> c != null &&
                        c.matches("(leitura|altera_codigo|altera_artefatos|publica|remove|agentes|executa_codigo|rede)"))
                ).limit(30).toList());
    }
    public List<RotinaPublica> rotinas() { return catalogo.rotinas(); }

    /**
     * Enfileira uma rotina do catálogo e descreve em uma frase o que aconteceu.
     *
     * <p>Mora aqui, e não na {@code RodarRotinaFerramenta}, porque há <b>dois</b> pedintes: a
     * ferramenta (o dono pedindo) e o {@code MonitorDeSites} (o Jarvis oferecendo depois de uma
     * queda). São as mesmas vinte e cinco linhas de enfileirar, esperar trinta segundos, pedir
     * aviso no timeout e traduzir cada saída — e duas cópias disso divergem na primeira correção
     * que alguém fizer num lado só.
     *
     * <p>Revalida catálogo e {@link #online()} <b>na hora da execução</b>, e não só no pedido: no
     * caminho da aprovação passam minutos entre o "Posso?" e o "pode", e o agente pode ter saído
     * do ar nesse meio.
     */
    public String iniciarRotina(String nome, String pedido, String canal, long usuario) {
        if (!online() || rotinas().stream().noneMatch(r -> r.nome().equals(nome)))
            return "Agente offline ou rotina removida; não iniciei.";
        ObjectNode dados = json.createObjectNode().put("pedido", pedido).put("confirmado", true);
        ComandoPc comando = new ComandoPc(java.util.UUID.randomUUID().toString(),
                "rodar_rotina", nome, Instant.now().toString(), dados);
        try {
            var futuro = enfileirar(comando, Duration.ofSeconds(60), Duration.ofHours(1), canal,
                    usuario);
            try {
                var resultado = futuro.get(30, java.util.concurrent.TimeUnit.SECONDS);
                return "Operação " + comando.id() + ": "
                        + (resultado.ok() ? "concluída" : "falhou") + ". " + resultado.mensagem();
            } catch (java.util.concurrent.TimeoutException espera) {
                pedirAviso(comando.id());
                return "Operação " + comando.id() + " em estado "
                        + estado(comando.id()).orElseThrow().estado()
                        + ". Consulte status_operacao_pc pelo ID; o resultado final será avisado.";
            }
        } catch (InterruptedException interrompida) {
            pedirAviso(comando.id());
            Thread.currentThread().interrupt();
            return "Operação " + comando.id() + " enviada; consulte o estado pelo ID.";
        } catch (Exception falha) {
            return "Não iniciei a rotina: " + falha.getMessage();
        }
    }

    @Scheduled(fixedRate = 10_000)
    public synchronized void varrer() {
        Instant agora = relogio.instant(); boolean mudou = false;
        for (Operacao o : new ArrayList<>(operacoes.values())) {
            if ("computer_acao".equals(o.comando.acao()) && o.resultado != null
                    && o.resultado.temImagem()
                    && agora.isAfter(o.atualizado.plus(Duration.ofMinutes(5)))) {
                descartarImagemComputer(o.comando.id());
            }
            if (o.finalizada()) {
                Duration retencao = "computer_acao".equals(o.comando.acao())
                        ? Duration.ofMinutes(5) : RETENCAO;
                if (agora.isAfter(o.atualizado.plus(retencao))) {
                    operacoes.remove(o.comando.id());
                    try {
                        Files.deleteIfExists(caminhoImagem(o.comando.id()));
                        Files.deleteIfExists(arquivoDeUpload(o.comando.id()));
                    } catch (IOException ignorada) { }
                    mudou = true;
                }
            } else if (!agora.isBefore(o.inicio) && List.of("pendente", "entregue").contains(o.estado)) {
                finalizar(o, "expirado", "prazo de início vencido"); mudou = true;
            } else if (!agora.isBefore(o.execucao.plusSeconds(60))
                    && List.of("recebido", "executando").contains(o.estado)) {
                finalizar(o, "resultado_incerto", "agente não confirmou o resultado após o prazo");
                mudou = true;
            } else if ((!agora.isBefore(o.execucao) && o.estado.equals("executando"))
                    || (!agora.isBefore(o.inicio) && o.estado.equals("recebido"))) {
                o.cancelar = true; o.atualizado = agora; mudou = true;
            }
        }
        if (mudou) salvar();
    }
    private void finalizar(Operacao o, String estado, String mensagem) {
        o.estado = estado; o.mensagem = mensagem; o.atualizado = relogio.instant();
        o.futuro.complete(new ResultadoPc(o.comando.id(), false, mensagem, null, null));
        salvar();
    }
    private void carregar() {
        if (!Files.isRegularFile(arquivo)) return;
        try {
            JsonNode raiz = json.readTree(Files.readString(arquivo));
            for (JsonNode n : raiz) {
                ComandoPc c = new ComandoPc(Json.texto(n.path("id")), Json.texto(n.path("acao")),
                        Json.texto(n.path("argumento")), Json.texto(n.path("criadoEm")), n.path("dados"));
                Operacao o = new Operacao(c, Instant.parse(Json.texto(n.path("inicio"))),
                        Instant.parse(Json.texto(n.path("execucao"))), Instant.parse(Json.texto(n.path("atualizado"))));
                o.estado = Json.texto(n.path("estado")); o.mensagem = Json.texto(n.path("mensagem"));
                o.cancelar = n.path("cancelar").asBoolean(false);
                o.avisoTentado = n.path("avisoTentado").asBoolean(false);
                o.avisoPendente = n.path("avisoPendente").asBoolean(false);
                o.canal = Json.texto(n.path("canal")); o.chatId = Json.numero(n.path("chatId"));
                if (n.path("codigoSaida").isNumber()) o.codigoSaida = (int) Json.numero(n.path("codigoSaida"));
                o.assinatura = Json.texto(n.path("assinatura"));
                if (n.path("temResultado").asBoolean(false)) {
                    byte[] imagem = Files.isRegularFile(caminhoImagem(c.id()))
                            ? Files.readAllBytes(caminhoImagem(c.id())) : null;
                    o.resultado = new ResultadoPc(c.id(), o.estado.equals("concluído"), o.mensagem,
                            imagem, Json.texto(n.path("mime")), n.path("arquivo").asBoolean(false),
                            n.path("resultadoDados").isMissingNode() ? null : n.path("resultadoDados"));
                }
                if (o.estado.equals("entregue")) o.estado = "pendente";
                // Após reinício, o diário do agente é quem reconcilia início e resultado.
                if (o.finalizada()) o.futuro.complete(o.resultado == null
                        ? new ResultadoPc(c.id(), o.estado.equals("concluído"), o.mensagem, null, null)
                        : o.resultado);
                operacoes.put(c.id(), o);
            }
        } catch (Exception e) { throw new IllegalStateException("diário E2 da VPS inválido", e); }
    }
    private void salvar() {
        try {
            Files.createDirectories(arquivo.getParent());
            ArrayNode lista = json.createArrayNode();
            for (Operacao o : operacoes.values()) {
                ObjectNode n = lista.addObject().put("id", o.comando.id()).put("acao", o.comando.acao())
                        .put("argumento", o.comando.argumento()).put("criadoEm", o.comando.criadoEm())
                        .put("inicio", o.inicio.toString()).put("execucao", o.execucao.toString())
                        .put("atualizado", o.atualizado.toString()).put("estado", o.estado)
                        .put("mensagem", o.mensagem).put("cancelar", o.cancelar)
                        .put("assinatura", o.assinatura).put("temResultado", o.resultado != null)
                        .put("avisoTentado", o.avisoTentado).put("avisoPendente", o.avisoPendente)
                        .put("canal", o.canal).put("chatId", o.chatId);
                if (o.comando.dados() != null && !("computer_acao".equals(o.comando.acao())
                        && o.finalizada())) n.set("dados", o.comando.dados());
                if (o.codigoSaida != null) n.put("codigoSaida", o.codigoSaida);
                if (o.resultado != null) {
                    n.put("arquivo", o.resultado.arquivo());
                    if (o.resultado.mime() != null) n.put("mime", o.resultado.mime());
                    if (o.resultado.dados() != null) n.set("resultadoDados", o.resultado.dados());
                }
            }
            Path temporario = arquivo.resolveSibling(arquivo.getFileName() + ".tmp");
            Files.writeString(temporario, json.writeValueAsString(lista));
            Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("não foi possível persistir E2", e); }
    }
    private Path caminhoImagem(String id) {
        return arquivo.getParent().resolve("imagens").resolve(id + ".img");
    }
    private void guardarImagem(String id, byte[] imagem) {
        try {
            Path destino = caminhoImagem(id);
            Files.createDirectories(destino.getParent());
            Path temporario = destino.resolveSibling(destino.getFileName() + ".tmp");
            Files.write(temporario, imagem);
            Files.move(temporario, destino, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) { throw new IllegalStateException("não foi possível persistir imagem E2", e); }
    }
    private static String assinar(String estado, ResultadoPc resultado, Integer codigo,
            String mensagemCompleta) {
        try {
            MessageDigest resumo = MessageDigest.getInstance("SHA-256");
            String cabecalho = estado + "\n" + resultado.ok() + "\n" + mensagemCompleta
                    + "\n" + codigo + "\n" + resultado.arquivo() + "\n" + resultado.mime()
                    + "\n" + (resultado.dados() == null ? "" : resultado.dados().toString());
            resumo.update(cabecalho.getBytes(StandardCharsets.UTF_8));
            if (resultado.imagem() != null) resumo.update(resultado.imagem());
            return java.util.HexFormat.of().formatHex(resumo.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
