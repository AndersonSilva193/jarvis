package dev.jarvis.conversa;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A pasta onde o histórico da conversa sobrevive ao restart do container: um arquivo por chat,
 * gravado pela troca atômica do {@link ArquivoAtomico} — o mesmo utilitário dos lembretes.
 *
 * Guarda as duas metades que antes só existiam em memória: os turnos que vão ao modelo (os mesmos
 * do {@code Assistente}, já sem blocos de pensamento) e as trocas públicas que a HUD mostra. São
 * dois recortes do mesmo diálogo e saem do disco juntos; separá-los em dois arquivos abriria a
 * janela de o modelo lembrar de um turno que a tela já esqueceu.
 *
 * <b>O que nunca entra:</b> bloco de pensamento (já vem fora) e imagem em base64 — uma captura de
 * tela são centenas de kilobytes que o modelo não vai reler e que ficariam parados num volume. No
 * lugar dela fica o texto {@code [imagem omitida]}.
 *
 * A gravação roda numa thread virtual própria, fora do caminho da resposta: quem está esperando o
 * Jarvis falar não paga o {@code fsync}. Quem chama só agenda depois do turno <b>concluído</b>, e
 * é isso que garante que turno pela metade nunca chega ao disco.
 */
public class HistoricoEmArquivo {

    private static final Logger log = LoggerFactory.getLogger(HistoricoEmArquivo.class);

    /** Teto de sanidade por chat: dez turnos de conversa não chegam perto disso. */
    private static final long MAX_BYTES = 4_000_000;

    private static final Pattern NOME = Pattern.compile("chat-(-?\\d{1,19})\\.json");

    /** Imagem embutida em texto (data URI) também sai: o prefixo basta para reconhecê-la. */
    private static final Pattern DATA_URI = Pattern.compile("data:image/[^;,\\s]+;base64,[A-Za-z0-9+/=]+");

    static final String IMAGEM_OMITIDA = "[imagem omitida]";

    /** O que estava salvo de um chat. Turnos e trocas já filtrados pela validade. */
    public record Salvo(long chatId, long usuario, List<List<ObjectNode>> turnos,
            List<RegistroConversas.Troca> trocas) { }

    private final Path pasta;
    private final Duration validade;
    private final JsonMapper json;
    private final ExecutorService gravacoes;

    public HistoricoEmArquivo(Path pasta, Duration validade, JsonMapper json) {
        this.pasta = pasta == null ? null : pasta.toAbsolutePath().normalize();
        this.validade = validade == null || validade.isNegative() ? Duration.ofHours(24) : validade;
        this.json = json;
        // Uma thread só, e de propósito: as gravações de um mesmo chat têm de sair na ordem em que
        // os turnos aconteceram, ou um turno antigo sobrescreveria o recente.
        this.gravacoes = pasta == null ? null
                : Executors.newSingleThreadExecutor(Thread.ofVirtual().name("historico").factory());
    }

    /** Sem pasta configurada o histórico volta a ser só memória, como era antes. */
    public static HistoricoEmArquivo desligado() {
        return new HistoricoEmArquivo(null, null, JsonMapper.builder().build());
    }

    public boolean ligado() {
        return pasta != null;
    }

    public Path pasta() {
        return pasta;
    }

    /**
     * Lê tudo que está na pasta. Arquivo ilegível é ignorado com WARN: um chat torto não impede o
     * Jarvis de subir com os outros.
     */
    public List<Salvo> carregarTudo() {
        if (!ligado() || !Files.isDirectory(pasta)) {
            return List.of();
        }
        List<Salvo> todos = new ArrayList<>();
        try (var arquivos = Files.list(pasta)) {
            for (Path arquivo : arquivos.toList()) {
                var nome = NOME.matcher(arquivo.getFileName().toString());
                if (!nome.matches()) {
                    continue;
                }
                try {
                    Salvo salvo = ler(arquivo, Long.parseLong(nome.group(1)));
                    if (salvo != null) {
                        todos.add(salvo);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Histórico salvo ignorado por estar ilegível: {}", e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("Não consegui listar a pasta de histórico ({}); começando sem histórico", e.getMessage());
        }
        return todos;
    }

    private Salvo ler(Path arquivo, long chatId) throws IOException {
        JsonNode raiz = json.readTree(ArquivoAtomico.ler(arquivo, MAX_BYTES));
        if (!raiz.isObject()) {
            throw new IOException("raiz do histórico não é um objeto JSON");
        }
        Instant limite = Instant.now().minus(validade);
        List<List<ObjectNode>> turnos = new ArrayList<>();
        for (JsonNode no : raiz.path("turnos")) {
            if (expirado(Json.texto(no.path("gravadoEm")), limite)) {
                continue;
            }
            List<ObjectNode> mensagens = new ArrayList<>();
            for (JsonNode mensagem : no.path("mensagens")) {
                if (mensagem.isObject()) {
                    mensagens.add((ObjectNode) mensagem);
                }
            }
            if (!mensagens.isEmpty()) {
                turnos.add(mensagens);
            }
        }
        List<RegistroConversas.Troca> trocas = new ArrayList<>();
        for (JsonNode no : raiz.path("trocas")) {
            try {
                RespostaConversa resposta = json.treeToValue(no.path("resposta"), RespostaConversa.class);
                if (expirado(resposta.concluidoEm(), limite)) {
                    continue;
                }
                trocas.add(new RegistroConversas.Troca(Json.texto(no.path("id")),
                        Json.texto(no.path("canal")), Json.texto(no.path("pedido")), resposta));
            } catch (RuntimeException e) {
                log.warn("Troca salva ignorada por estar inválida: {}", e.getMessage());
            }
        }
        if (turnos.isEmpty() && trocas.isEmpty()) {
            return null;
        }
        return new Salvo(chatId, (long) Json.numero(raiz.path("usuario")), turnos, trocas);
    }

    /** Sem data ou com data torta o turno é tratado como expirado: em dúvida, não restaura. */
    private boolean expirado(String quando, Instant limite) {
        try {
            return Instant.parse(quando).isBefore(limite);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Agenda a gravação do chat fora do caminho da resposta. Chame só com o turno concluído: o
     * que for passado aqui é o que vai ao disco.
     */
    public void agendar(long chatId, long usuario, List<List<ObjectNode>> turnos,
            List<RegistroConversas.Troca> trocas) {
        if (!ligado()) {
            return;
        }
        // A cópia é feita aqui, na thread do turno: o Assistente segue mexendo no seu Deque, e a
        // gravação não pode ler uma lista que muda embaixo dela.
        String conteudo;
        try {
            conteudo = montar(usuario, turnos, trocas);
        } catch (RuntimeException e) {
            log.warn("Não consegui montar o histórico do chat {} para gravar: {}", chatId, e.getMessage());
            return;
        }
        naFila(false, () -> {
            try {
                ArquivoAtomico.gravar(arquivoDe(chatId), conteudo);
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui gravar o histórico do chat {}: {}", chatId, e.getMessage());
            }
        });
    }

    /**
     * Enfileira o trabalho de disco na thread única, esperando por ele quando o chamador precisa
     * do resultado já feito.
     *
     * Apagar <b>também</b> passa pela fila, e não é detalhe: uma gravação do turno anterior ainda
     * pendente rodaria depois do delete e ressuscitaria o arquivo que o dono mandou esquecer.
     */
    private void naFila(boolean esperar, Runnable trabalho) {
        try {
            var futuro = gravacoes.submit(trabalho);
            if (esperar) {
                futuro.get(5, TimeUnit.SECONDS);
            }
        } catch (java.util.concurrent.RejectedExecutionException desligando) {
            // Fila fechada: o processo está saindo. Apagar não pode esperar pelo próximo restart.
            if (esperar) {
                trabalho.run();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            log.warn("Trabalho de histórico não terminou: {}", e.getMessage());
        }
    }

    /** Espera a fila esvaziar. Existe para os testes conferirem o que foi gravado. */
    public void aguardar() {
        if (ligado()) {
            naFila(true, () -> { });
        }
    }

    /** Grava na hora, sem passar pelo executor. Existe para os testes montarem um estado salvo. */
    public void gravarAgora(long chatId, long usuario, List<List<ObjectNode>> turnos,
            List<RegistroConversas.Troca> trocas) throws IOException {
        if (ligado()) {
            ArquivoAtomico.gravar(arquivoDe(chatId), montar(usuario, turnos, trocas));
        }
    }

    private String montar(long usuario, List<List<ObjectNode>> turnos,
            List<RegistroConversas.Troca> trocas) {
        String agora = Instant.now().toString();
        ObjectNode raiz = json.createObjectNode();
        raiz.put("usuario", usuario);
        raiz.put("gravadoEm", agora);
        ArrayNode lista = raiz.putArray("turnos");
        for (List<ObjectNode> turno : turnos) {
            ObjectNode no = lista.addObject();
            // A data é do turno, não do arquivo: a expiração descarta turno por turno.
            no.put("gravadoEm", agora);
            ArrayNode mensagens = no.putArray("mensagens");
            for (ObjectNode mensagem : turno) {
                mensagens.add(semImagens(mensagem));
            }
        }
        ArrayNode publicas = raiz.putArray("trocas");
        for (RegistroConversas.Troca troca : trocas) {
            ObjectNode no = publicas.addObject();
            no.put("id", troca.id());
            no.put("canal", troca.canal());
            no.put("pedido", troca.pedido());
            no.set("resposta", json.valueToTree(troca.resposta()));
        }
        return json.writeValueAsString(raiz);
    }

    /**
     * Troca toda imagem por {@code [imagem omitida]}: o bloco {@code image} inteiro, inclusive o
     * aninhado dentro de um {@code tool_result}, e o data URI escrito no meio de um texto.
     */
    private ObjectNode semImagens(ObjectNode mensagem) {
        ObjectNode copia = mensagem.deepCopy();
        JsonNode conteudo = copia.path("content");
        if (conteudo.isString()) {
            copia.put("content", DATA_URI.matcher(conteudo.stringValue()).replaceAll(IMAGEM_OMITIDA));
        } else if (conteudo.isArray()) {
            copia.set("content", limparBlocos((ArrayNode) conteudo));
        }
        return copia;
    }

    private ArrayNode limparBlocos(ArrayNode blocos) {
        ArrayNode limpos = json.createArrayNode();
        for (JsonNode bloco : blocos) {
            String tipo = Json.texto(bloco.path("type"));
            if ("image".equals(tipo) || "input_image".equals(tipo)) {
                limpos.add(json.createObjectNode().put("type", "text").put("text", IMAGEM_OMITIDA));
                continue;
            }
            if (!bloco.isObject()) {
                limpos.add(bloco);
                continue;
            }
            ObjectNode copia = ((ObjectNode) bloco).deepCopy();
            if (copia.path("content").isArray()) {
                copia.set("content", limparBlocos((ArrayNode) copia.path("content")));
            } else if (copia.path("content").isString()) {
                copia.put("content", DATA_URI.matcher(copia.path("content").stringValue())
                        .replaceAll(IMAGEM_OMITIDA));
            }
            if (copia.path("text").isString()) {
                copia.put("text", DATA_URI.matcher(copia.path("text").stringValue())
                        .replaceAll(IMAGEM_OMITIDA));
            }
            limpos.add(copia);
        }
        return limpos;
    }

    /**
     * Apaga o arquivo do chat <b>na hora</b>, na thread de quem chamou. Privacidade não se agenda:
     * o pedido de esquecer tem de deixar o disco limpo antes de o Jarvis responder que esqueceu.
     */
    public void apagar(long chatId) {
        if (!ligado()) {
            return;
        }
        naFila(true, () -> {
            try {
                ArquivoAtomico.apagar(arquivoDe(chatId));
            } catch (IOException | RuntimeException e) {
                log.warn("Não consegui apagar o histórico do chat {}: {}", chatId, e.getMessage());
            }
        });
    }

    public void apagar(Collection<Long> chatIds) {
        for (long chatId : chatIds) {
            apagar(chatId);
        }
    }

    /** Espera as gravações pendentes antes de o processo sair, para não perder o último turno. */
    public void fechar() {
        if (!ligado()) {
            return;
        }
        gravacoes.shutdown();
        try {
            if (!gravacoes.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Gravação de histórico não terminou em 5 s; seguindo com o desligamento");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    Path arquivoDe(long chatId) {
        return pasta.resolve("chat-" + chatId + ".json");
    }
}
