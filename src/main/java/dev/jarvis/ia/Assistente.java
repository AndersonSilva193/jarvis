package dev.jarvis.ia;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.beans.factory.annotation.Autowired;
import dev.jarvis.conversa.*;
import dev.jarvis.ferramentas.ResultadoFerramenta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.jarvis.ferramentas.Ferramentas;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O "cérebro": recebe o pedido, conversa com o Claude e executa as ferramentas que ele pedir,
 * repetindo até o modelo dar a resposta final (o chamado "loop de agente").
 */
@Service
public class Assistente {

    /** Limite de idas e vindas com ferramentas por pedido, para nunca entrar em loop infinito. */
    private static final int MAX_PASSOS = 8;
    /** Quantas trocas (pedido + resposta) o Jarvis lembra por conversa. Fica em memória. */
    private static final int MAX_TURNOS_HISTORICO = 10;

    private static final Logger log = LoggerFactory.getLogger(Assistente.class);

    private final ClaudeClient claude;
    private final Ferramentas ferramentas;
    private final SistemaDoJarvis sistema;
    private final JsonMapper json;
    private final RegistroConversas conversas;
    private final AnexosService anexos;
    private final BuscaWebServidor buscaWeb;
    private final ReentrantLock execucao = new ReentrantLock(true);

    /** Cada turno é a lista de mensagens de um pedido (texto do usuário, chamadas de ferramenta e resposta). */
    private final Map<Long, Deque<List<ObjectNode>>> historicos = new HashMap<>();
    private final Map<Long, Long> usuarioPorChat = new HashMap<>();

    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json) {
        this(claude, ferramentas, sistema, json, new RegistroConversas(evento -> { }), new AnexosService(),
                BuscaWebServidor.desligada());
    }

    @Autowired
    public Assistente(ClaudeClient claude, Ferramentas ferramentas, SistemaDoJarvis sistema, JsonMapper json,
            RegistroConversas conversas, AnexosService anexos, BuscaWebServidor buscaWeb) {
        this.claude = claude;
        this.ferramentas = ferramentas;
        this.sistema = sistema;
        this.json = json;
        this.conversas = conversas;
        this.anexos = anexos;
        this.buscaWeb = buscaWeb;
    }

    /** Responde a uma mensagem vinda do Telegram. */
    public String responder(long chatId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM);
    }

    /** Entrada autenticada pelo poller; update_id é estável em uma repetição da entrega. */
    public String responderTelegram(long chatId, long usuario, long updateId, String texto) throws Exception {
        return responder(chatId, texto, Canal.TELEGRAM, new OuvinteProgresso() {
            @Override public String idPedido() { return "telegram-" + updateId; }
            // Em grupo o remetente pode ser autorizado, mas a resposta seria pública.
            @Override public long usuarioAutenticado() { return chatId == usuario ? usuario : 0; }
        }).texto();
    }

    /** Responde a uma mensagem da conversa, usando e atualizando o histórico daquele chat. */
    public String responder(long chatId, String texto, Canal canal) throws Exception {
        return responder(chatId, texto, canal, null).texto();
    }

    public RespostaConversa responder(long chatId, String texto, Canal canal, OuvinteProgresso ouvinte) throws Exception {
        OuvinteProgresso origem = ouvinte == null ? new OuvinteProgresso() { } : ouvinte;
        java.util.concurrent.atomic.AtomicBoolean apagou = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<String> apagadoId = new java.util.concurrent.atomic.AtomicReference<>("");
        java.util.concurrent.atomic.AtomicLong apagadoRevisao = new java.util.concurrent.atomic.AtomicLong();
        OuvinteProgresso progresso = new OuvinteProgresso() {
            @Override public String idPedido() { return origem.idPedido(); }
            @Override public long usuarioAutenticado() { return origem.usuarioAutenticado(); }
            @Override public boolean memoriaEsquecida() { return apagou.get(); }
            @Override public void marcarMemoriaEsquecida() { apagou.set(true); }
            @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                apagou.set(true); apagadoId.set(id); apagadoRevisao.set(revisao);
            }
            @Override public String idMemoriaEsquecida() { return apagadoId.get(); }
            @Override public long revisaoMemoriaEsquecida() { return apagadoRevisao.get(); }
            @Override public void iniciado() { origem.iniciado(); }
            @Override public void ferramenta(String nome, String descricao, Integer passo, Integer total) {
                origem.ferramenta(nome, descricao, passo, total);
            }
        };
        String id = progresso.idPedido() == null ? UUID.randomUUID().toString() : progresso.idPedido();
        execucao.lockInterruptibly();
        try {
            if (canal == Canal.HUD && progresso.idPedido() != null && !conversas.pendente(chatId, id))
                throw new IllegalStateException("Pedido encerrado pela limpeza do histórico");
            OuvinteProgresso.avisar(progresso::iniciado);
            if (progresso.usuarioAutenticado() > 0) usuarioPorChat.put(chatId, progresso.usuarioAutenticado());
            Deque<List<ObjectNode>> historico = historicos.computeIfAbsent(chatId, chave -> new ArrayDeque<>());
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(texto));

            List<ResultadoFerramenta> resultados = new ArrayList<>();
            String resposta;
            var confirmacaoMemoria = java.util.regex.Pattern.compile(
                    "(?i)^confirmo esquecer ([0-9a-f-]{36}) ([0-9]{6})$").matcher(texto.trim());
            if (confirmacaoMemoria.matches() && progresso.usuarioAutenticado() > 0) {
                ObjectNode chamada = json.createObjectNode().put("type", "tool_use")
                        .put("id", "esquecer_" + id).put("name", "esquecer_memoria");
                chamada.putObject("input").put("id", confirmacaoMemoria.group(1))
                        .put("codigo", confirmacaoMemoria.group(2));
                OuvinteProgresso autenticado = new OuvinteProgresso() {
                    @Override public String idPedido() { return id; }
                    @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                    @Override public String canal() { return canal.nome(); }
                    @Override public String pedidoOriginal() { return texto; }
                    @Override public void marcarMemoriaEsquecida(String memoriaId, long revisao) {
                        progresso.marcarMemoriaEsquecida(memoriaId, revisao);
                    }
                };
                resposta = Json.texto(ferramentas.executar(chamada, autenticado, resultados::add).path("content"));
                turno.add(json.createObjectNode().put("role", "assistant").put("content", resposta));
            } else if (ComandoDeRepouso.reconhece(texto)) {
                ObjectNode chamada = json.createObjectNode().put("type", "tool_use")
                        .put("id", "repouso_" + id).put("name", "controlar_pc");
                chamada.putObject("input").put("acao", "modo_repouso");
                ObjectNode resultado = ferramentas.executar(chamada, progresso, resultados::add);
                resposta = Json.texto(resultado.path("content"));
                turno.add(json.createObjectNode().put("role", "assistant").put("content", resposta));
            } else {
                resposta = executarLoop(historico, turno, canal, progresso, resultados);
            }
            List<Anexo> publicados = new ArrayList<>();
            Confirmacao confirmacao = null;
            EscutaResposta escuta = null;
            for (ResultadoFerramenta resultado : resultados) {
                for (var artefato : resultado.artefatos()) {
                    try { publicados.add(anexos.registrar(artefato)); }
                    catch (java.io.IOException e) { resposta += "\nNão consegui disponibilizar um dos anexos."; }
                }
                for (var referencia : resultado.referencias())
                    anexos.referencia(referencia.nome(), referencia.url()).ifPresent(publicados::add);
                if (resultado.confirmacao() != null) confirmacao = resultado.confirmacao();
                if (resultado.escuta() != null) escuta = resultado.escuta();
            }
            Instant agora = Instant.now();
            if (progresso.memoriaEsquecida()) resposta = "Memória esquecida: id="
                    + progresso.idMemoriaEsquecida() + " revisão=" + progresso.revisaoMemoriaEsquecida();
            if (canal != Canal.HUD) escuta = null;
            else if (confirmacao != null) escuta = escutaDaConfirmacao(confirmacao, agora);
            RespostaConversa publica = new RespostaConversa(id, canal.nome(), resposta, publicados,
                    confirmacao, agora.toString(), null, escuta);

            // Só guardamos o turno se ele terminou bem, assim o histórico nunca fica "pela metade".
            if (progresso.memoriaEsquecida()) {
                for (var entrada : new ArrayList<>(usuarioPorChat.entrySet())) {
                    if (entrada.getValue() == progresso.usuarioAutenticado()) {
                        historicos.remove(entrada.getKey());
                        conversas.limpar(entrada.getKey());
                    }
                }
                conversas.concluirSemHistorico(chatId, publica);
            } else {
                historico.addLast(semPensamentos(turno));
                while (historico.size() > MAX_TURNOS_HISTORICO) historico.removeFirst();
                conversas.concluir(chatId, texto, publica);
            }
            return publica;
        } finally { execucao.unlock(); }
    }

    /** A confirmação determina a janela de escuta, inclusive quando já expirou. */
    static EscutaResposta escutaDaConfirmacao(Confirmacao confirmacao, Instant agora) {
        int segundos = 0;
        if (confirmacao.expiraEm() != null) {
            try {
                segundos = (int) Math.clamp(Duration.between(agora,
                        Instant.parse(confirmacao.expiraEm())).getSeconds(), 0L, 20L);
            } catch (DateTimeParseException ignorada) {
                // Prazo inválido não pode abrir uma janela de confirmação sem validade.
            }
        }
        return new EscutaResposta(segundos, List.of(confirmacao.codigo(), "cancelar"), confirmacao.descricao());
    }

    /** Executa uma instrução avulsa sem misturar com o histórico da conversa. */
    public String executarTarefa(String instrucao) throws Exception {
        return executarTarefa(instrucao, Canal.TAREFA);
    }

    /**
     * Executa uma instrução avulsa (ex.: tarefas agendadas, briefing falado, HUD) sem misturar com o
     * histórico da conversa. O canal decide só o formato do texto que volta.
     */
    public String executarTarefa(String instrucao, Canal canal) throws Exception {
        execucao.lockInterruptibly();
        try {
            List<ObjectNode> turno = new ArrayList<>();
            turno.add(mensagemDoUsuario(instrucao));
            return executarLoop(new ArrayDeque<>(), turno, canal, new OuvinteProgresso() { }, new ArrayList<>());
        } finally { execucao.unlock(); }
    }

    public void limparHistorico(long chatId) {
        execucao.lock();
        try {
            historicos.remove(chatId);
            conversas.limpar(chatId);
        } finally { execucao.unlock(); }
    }

    private String executarLoop(Deque<List<ObjectNode>> historico, List<ObjectNode> turno, Canal canal,
            OuvinteProgresso progresso, List<ResultadoFerramenta> detalhados)
            throws Exception {
        // Montado UMA vez por pedido, de propósito. O bloco dinâmico tem a hora: remontá-lo a cada
        // ida e vinda de ferramenta mudaria o system no meio do turno, e o modelo rejeita (HTTP 400)
        // os blocos de pensamento que ele devolveu sob o system anterior.
        ArrayNode system = sistema.blocos(canal);
        // As ferramentas também: elas são renderizadas antes do system e entram no mesmo prefixo
        // do cache de prompt. A da busca na web é server-side — quem executa é a Anthropic.
        ArrayNode definicoes = ferramentas.definicoes(canal);
        if (buscaWeb.ativa()) {
            definicoes.add(buscaWeb.definicao());
        }
        int buscas = 0;
        java.util.concurrent.atomic.AtomicInteger restante = new java.util.concurrent.atomic.AtomicInteger(4_000);
        java.util.concurrent.atomic.AtomicBoolean esqueceu = new java.util.concurrent.atomic.AtomicBoolean();
        Map<String, String> evidencias = new HashMap<>();
        boolean memoriaLida = false;
        String ultimoErroDeArquivo = "";
        String ultimaFalhaDeArquivo = "";

        for (int passo = 0; passo < MAX_PASSOS; passo++) {
            ArrayNode mensagens = json.createArrayNode();
            for (List<ObjectNode> turnoAnterior : historico) {
                for (ObjectNode m : turnoAnterior) {
                    mensagens.add(m);
                }
            }
            for (ObjectNode m : turno) {
                mensagens.add(m);
            }

            JsonNode resposta = claude.criarMensagem(system, mensagens, definicoes);
            buscas += (int) Json.numero(resposta.path("usage").path("server_tool_use")
                    .path("web_search_requests"));
            JsonNode conteudo = resposta.path("content");

            ObjectNode doAssistente = json.createObjectNode();
            doAssistente.put("role", "assistant");
            if (conteudo.isEmpty()) {
                // A API não aceita mensagem vazia no histórico.
                doAssistente.put("content", "(sem resposta)");
            } else {
                doAssistente.set("content", conteudo);
            }
            turno.add(doAssistente);

            String motivo = Json.texto(resposta.path("stop_reason"));
            // A API pausa turno longo de busca. Continuar é reenviar a mensagem do assistente
            // inalterada — ela já foi anexada crua ao turno logo acima.
            if ("pause_turn".equals(motivo)) {
                continue;
            }
            if (!"tool_use".equals(motivo)) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }

            // O modelo pediu uma ou mais ferramentas: executamos e devolvemos os resultados.
            ArrayNode resultados = json.createArrayNode();
            String erroRepetidoDeArquivo = "";
            boolean pesquisaFeita = buscas > 0;
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                if ("tool_use".equals(Json.texto(bloco.path("type")))) {
                    String nomeFerramenta = Json.texto(bloco.path("name"));
                    boolean ferramentaDeMemoria = List.of("buscar_memoria", "ler_memoria",
                            "registrar_memoria", "atualizar_memoria", "esquecer_memoria").contains(nomeFerramenta);
                    if (memoriaLida && !ferramentaDeMemoria) {
                        resultados.add(json.createObjectNode().put("type", "tool_result")
                                .put("tool_use_id", Json.texto(bloco.path("id")))
                                .put("content", "Memória lida não autoriza outras ferramentas neste pedido. Faça um novo pedido explícito.")
                                .put("is_error", true));
                        continue;
                    }
                    OuvinteProgresso seguro = new OuvinteProgresso() {
                        // O canal vem daqui porque a ferramenta não recebe o Canal: é por ele que
                        // o card da tela sabe se o pedido veio do celular ou da própria HUD.
                        @Override public String canal() { return canal.nome(); }
                        @Override public String idPedido() { return progresso.idPedido(); }
                        @Override public long usuarioAutenticado() { return progresso.usuarioAutenticado(); }
                        @Override public String pedidoOriginal() { return Json.texto(turno.getFirst().path("content")); }
                        @Override public boolean memoriaEsquecida() { return esqueceu.get(); }
                        @Override public void marcarMemoriaEsquecida() { esqueceu.set(true); progresso.marcarMemoriaEsquecida(); }
                        @Override public void marcarMemoriaEsquecida(String id, long revisao) {
                            esqueceu.set(true); progresso.marcarMemoriaEsquecida(id, revisao);
                        }
                        @Override public boolean consumirContexto(int caracteres) {
                            if (caracteres < 0) return false;
                            while (true) {
                                int atual = restante.get();
                                if (atual < caracteres) return false;
                                if (restante.compareAndSet(atual, atual - caracteres)) return true;
                            }
                        }
                        @Override public boolean evidenciaVerificada(String texto) {
                            return !origemDaEvidencia(texto).isEmpty();
                        }
                        @Override public String origemDaEvidencia(String texto) {
                            return evidencias.entrySet().stream().filter(e -> e.getKey().contains(texto))
                                    .map(e -> "ferramenta:" + e.getValue()).findFirst().orElse("");
                        }
                        @Override public boolean pesquisaFeita() { return pesquisaFeita; }
                        @Override public void ferramenta(String nome, String descricao, Integer atual, Integer total) {
                            OuvinteProgresso.avisar(() -> progresso.ferramenta(nome, descricao, atual, total));
                        }
                    };
                    ObjectNode resultado = ferramentas.executar(bloco, seguro, detalhados::add);
                    if (List.of("buscar_memoria", "ler_memoria").contains(nomeFerramenta)) memoriaLida = true;
                    if (List.of("verificar_site", "status_servidor", "clima").contains(Json.texto(bloco.path("name")))
                            && !resultado.path("is_error").asBoolean(false))
                        evidencias.put(Json.texto(resultado.path("content")), nomeFerramenta);
                    resultados.add(resultado);
                    if ("criar_arquivo".equals(nomeFerramenta)
                            || "criar_planilha_google".equals(nomeFerramenta)) {
                        String textoResultado = Json.texto(resultado.path("content"));
                        ultimoErroDeArquivo = textoResultado.startsWith("Arquivo criado:")
                                || textoResultado.startsWith("Planilha criada:") ? ""
                                : textoResultado.substring(0, Math.min(240, textoResultado.length()));
                        if (!ultimoErroDeArquivo.isBlank()) {
                            String assinatura = nomeFerramenta + ":" + bloco.path("input") + ":" + ultimoErroDeArquivo;
                            if (assinatura.equals(ultimaFalhaDeArquivo)) erroRepetidoDeArquivo = ultimoErroDeArquivo;
                            ultimaFalhaDeArquivo = assinatura;
                        } else ultimaFalhaDeArquivo = "";
                    }
                }
            }
            // Os blocos server_tool_use e web_search_tool_result já vêm resolvidos pelo servidor da
            // Anthropic e não passam pelo registro de ferramentas. Se o turno só tinha desses, não
            // há resultado para devolver — e mensagem de content vazio a API recusa.
            if (resultados.isEmpty()) {
                registrarBuscas(buscas);
                return extrairTexto(conteudo);
            }
            ObjectNode comResultados = json.createObjectNode();
            comResultados.put("role", "user");
            comResultados.set("content", resultados);
            turno.add(comResultados);
            if (esqueceu.get()) return "Memória esquecida.";
            // Repetir a mesma entrada recusada desperdiça chamadas do modelo e acabava no erro
            // genérico da HUD. O turno já tem o tool_result, então o histórico continua válido.
            if (!erroRepetidoDeArquivo.isBlank()) return erroRepetidoDeArquivo;
        }
        registrarBuscas(buscas);
        throw new IllegalStateException("precisei de passos demais para resolver isso. "
                + (ultimoErroDeArquivo.isBlank() ? "Tente um pedido mais simples." : ultimoErroDeArquivo));
    }

    /**
     * Quantas buscas na web o pedido gastou. Cada uma é cobrada à parte dos tokens, então bater no
     * teto merece aviso: é o sinal de pedido caro, ou de pergunta que não precisava de busca.
     */
    private void registrarBuscas(int buscas) {
        if (buscas <= 0) {
            return;
        }
        if (buscaWeb.maxUsos() > 0 && buscas >= buscaWeb.maxUsos()) {
            log.warn("Busca na web: {} buscas neste pedido, o teto (BUSCA_WEB_MAX_USOS)", buscas);
        } else {
            log.info("Busca na web: {} busca(s) neste pedido", buscas);
        }
    }

    /**
     * Copia o turno sem os blocos de pensamento ("thinking") do modelo, que é a forma que guardamos
     * no histórico.
     *
     * <p>Cada bloco desses vem assinado e preso ao prefixo exato que o produziu — system, ferramentas
     * e mensagens anteriores. Como o nosso system carrega a hora e o histórico tem janela deslizante
     * (os turnos mais velhos caem fora), esse prefixo muda entre um pedido e outro, e a API recusa o
     * bloco com HTTP 400 ("block is bound to a different conversation"). Dentro do turno em curso eles
     * continuam intactos, que é o que o laço de ferramentas exige; o que sai é sempre tudo que é
     * anterior, nunca um pedaço do meio.
     */
    private List<ObjectNode> semPensamentos(List<ObjectNode> turno) {
        List<ObjectNode> limpo = new ArrayList<>(turno.size());
        for (ObjectNode mensagem : turno) {
            JsonNode conteudo = mensagem.path("content");
            if (!conteudo.isArray()) {
                limpo.add(mensagem);
                continue;
            }
            ArrayNode mantidos = json.createArrayNode();
            for (int i = 0; i < conteudo.size(); i++) {
                JsonNode bloco = conteudo.get(i);
                String tipo = Json.texto(bloco.path("type"));
                // Só o pensamento sai. Os blocos de busca (server_tool_use, web_search_tool_result)
                // ficam: o encrypted_content deles é o que a API decifra nos turnos seguintes, e
                // mexer nele — ou deixá-lo de fora — é HTTP 400.
                if (!"thinking".equals(tipo) && !"redacted_thinking".equals(tipo)) {
                    mantidos.add(bloco);
                }
            }
            ObjectNode copia = mensagem.deepCopy();
            // Mensagem sem conteúdo nenhum a API recusa; sobra o texto de marcação.
            if (mantidos.isEmpty()) {
                copia.put("content", "(sem resposta)");
            } else {
                copia.set("content", mantidos);
            }
            limpo.add(copia);
        }
        return limpo;
    }

    private ObjectNode mensagemDoUsuario(String texto) {
        ObjectNode m = json.createObjectNode();
        m.put("role", "user");
        m.put("content", texto);
        return m;
    }

    private String extrairTexto(JsonNode conteudo) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < conteudo.size(); i++) {
            JsonNode bloco = conteudo.get(i);
            if ("text".equals(Json.texto(bloco.path("type")))) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(Json.texto(bloco.path("text")));
            }
        }
        return sb.isEmpty() ? "(sem resposta)" : sb.toString().trim();
    }

}
