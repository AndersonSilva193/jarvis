package dev.jarvis.memoria;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.ClaudeClient;
import dev.jarvis.util.Json;
import dev.jarvis.util.Texto;
import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;

/**
 * O Jarvis anotando sozinho o que o dono conta.
 *
 * As ferramentas de memória atendem a pedido explícito. Isto atende ao resto: o dono conta um
 * projeto novo, uma preferência, uma decisão, e nada disso ficava porque ele não disse "anote".
 * O anotador junta as mensagens <b>do usuário</b> de cada conversa, e quando o trecho fecha — por
 * ociosidade ou por contagem de turnos — faz uma chamada curta ao Claude pedindo candidatos.
 *
 * <b>O modelo propõe; o servidor decide.</b> Nenhum candidato é gravado sem passar por todas as
 * travas de {@link #analisar}, e a mais importante delas é a prova de citação: a evidência tem de
 * ser trecho literal de uma mensagem do usuário daquele trecho. Sem isso o modelo poderia inventar
 * um fato e gravá-lo como se o dono tivesse dito.
 *
 * O que foi anotado entra numa fila por chat e sai numa linha na resposta seguinte
 * ({@link #drenarAviso}) — memória que cresce calada é memória que o dono não consegue corrigir.
 */
@Component
public class AnotadorDeMemoria {
    private static final Logger log = LoggerFactory.getLogger(AnotadorDeMemoria.class);

    /** Turnos que fecham o trecho sem esperar a conversa parar. */
    private static final int TURNOS_POR_TRECHO = 10;
    private static final int MAX_CANDIDATOS = 5;
    /** Teto de mensagens no prompt: trecho gigante é caro e dilui o que importa. */
    private static final int MAX_MENSAGENS = 24;
    private static final int MAX_CARACTERES_POR_MENSAGEM = 1_500;
    private static final int MAX_TOKENS = 900;
    private static final Duration PRAZO = Duration.ofSeconds(20);
    /** Aviso velho é descartado: "Anotei" no dia seguinte não explica mais nada. */
    private static final Duration VALIDADE_AVISO = Duration.ofHours(1);
    private static final int CURTO = 80;

    private static final String SISTEMA = """
            Você extrai fatos duráveis das mensagens de uma pessoa para a memória de longo prazo do
            assistente dela. Responda APENAS com JSON, no formato:
            {"candidatos":[{"categoria":"perfil|preferencias|projeto|episodio","projeto":"slug-minusculo",
            "assunto":"...","conteudo":"...","evidencia":"...","acao":"novo|atualizar","idExistente":"..."}]}

            No máximo 5 candidatos. Nada a anotar é uma resposta boa: {"candidatos":[]}.

            Anote somente:
            - fato durável que a própria pessoa disse sobre ela, seus projetos, preferências,
              decisões e as pessoas da vida dela.
            Nunca anote:
            - o que expira sozinho (o que ela vai fazer hoje, onde está agora, o clima, o humor
              de agora, uma pergunta que ela fez);
            - o que o assistente sugeriu e ela não confirmou;
            - o que você deduziu e ela não disse.

            Saúde, finanças e pessoas PODEM ser anotados quando foi ela que contou — é a memória
            dela, e omitir o que ela disse não a protege de nada.

            Campos:
            - "categoria": "perfil" (quem ela é), "preferencias" (como ela gosta das coisas),
              "projeto" (precisa de "projeto" com um slug minúsculo) ou "episodio" (algo que
              aconteceu e vale lembrar).
            - "assunto": o rótulo curto do fato, estável entre conversas ("cidade", "editor",
              "stack do agendabarber"). É por ele que se reencontra o registro.
            - "conteudo": o fato em uma ou duas frases, por extenso, sem depender do trecho.
            - "evidencia": um TRECHO LITERAL, copiado exatamente de uma mensagem da pessoa, que
              prova o fato. Não reescreva, não corrija, não junte pedaços de duas mensagens.
              Candidato cuja evidência não aparecer literalmente na conversa é descartado.
            - "acao" e "idExistente": informativos. Quando o assunto já estiver na lista de
              assuntos gravados, use "atualizar" e repita o id dele.
            """;

    private final MemoriaService memoria;
    private final ClaudeClient claude;
    private final JsonMapper json;
    private final JarvisProperties props;
    private final Clock relogio;

    private final ExecutorService tarefas = Executors.newVirtualThreadPerTaskExecutor();

    /** O trecho em formação de cada conversa: só mensagens do usuário, e o instante da última. */
    private record Chave(long chat, long usuario, String canal) { }
    private record Trecho(List<String> mensagens, Instant ultima) { }
    private final Map<Chave, Trecho> trechos = new HashMap<>();
    /** Uma análise por conversa de cada vez: o gatilho de ociosidade pode cair sobre o de turnos. */
    private final Set<Chave> emAnalise = new HashSet<>();

    /** O que foi anotado, esperando a próxima resposta. Os ids e o instante servem ao desfazer. */
    public record Anotado(List<String> ids, List<String> conteudos, Instant quando) { }
    private final Map<Long, Anotado> avisos = new HashMap<>();

    @Autowired
    public AnotadorDeMemoria(MemoriaService memoria, ClaudeClient claude, JsonMapper json,
                             JarvisProperties props) {
        this(memoria, claude, json, props, Clock.systemUTC());
    }

    public AnotadorDeMemoria(MemoriaService memoria, ClaudeClient claude, JsonMapper json,
                             JarvisProperties props, Clock relogio) {
        this.memoria = memoria;
        this.claude = claude;
        this.json = json;
        this.props = props;
        this.relogio = relogio;
    }

    /**
     * Um turno que terminou bem. A mensagem é a do usuário, como ela chegou — nunca a fala do
     * Jarvis, nem resultado de ferramenta, busca, arquivo ou página.
     *
     * Só conversa privada com usuário identificado: {@code usuario > 0} é o portão que o resto do
     * sistema também usa, e em grupo a resposta seria pública.
     */
    public void registrarTurno(long chatId, long usuario, String canal, String mensagemDoUsuario) {
        if (!props.anotadorDeMemoriaAtivo() || usuario <= 0
                || !("telegram".equals(canal) || "hud".equals(canal))
                || mensagemDoUsuario == null || mensagemDoUsuario.isBlank()) {
            return;
        }
        Chave chave = new Chave(chatId, usuario, canal);
        List<String> fechado = null;
        synchronized (this) {
            Trecho atual = trechos.get(chave);
            List<String> mensagens = atual == null ? new ArrayList<>() : new ArrayList<>(atual.mensagens());
            mensagens.add(cortar(mensagemDoUsuario.trim()));
            if (mensagens.size() >= TURNOS_POR_TRECHO && !emAnalise.contains(chave)) {
                trechos.remove(chave);
                emAnalise.add(chave);
                fechado = mensagens;
            } else {
                trechos.put(chave, new Trecho(mensagens, relogio.instant()));
            }
        }
        if (fechado != null) {
            despachar(chave, fechado);
        }
    }

    /**
     * Conversa parada fecha o trecho. Roda a cada minuto, então o corte de
     * {@code MEMORIA_ANOTADOR_OCIOSO_MINUTOS} tem a folga de um minuto — para "a conversa parou"
     * isso é preciso de sobra, e um agendamento por conversa não valeria a complicação.
     */
    @Scheduled(fixedDelay = 60_000)
    void verificarOciosos() {
        if (!props.anotadorDeMemoriaAtivo()) {
            return;
        }
        Map<Chave, List<String>> fechados = new LinkedHashMap<>();
        Instant agora = relogio.instant();
        Duration ocioso = props.ociosidadeDoAnotador();
        synchronized (this) {
            for (var entrada : new ArrayList<>(trechos.entrySet())) {
                Chave chave = entrada.getKey();
                if (emAnalise.contains(chave)
                        || Duration.between(entrada.getValue().ultima(), agora).compareTo(ocioso) < 0) {
                    continue;
                }
                trechos.remove(chave);
                emAnalise.add(chave);
                fechados.put(chave, entrada.getValue().mensagens());
            }
            avisos.entrySet().removeIf(e -> expirado(e.getValue(), agora));
        }
        fechados.forEach(this::despachar);
    }

    private void despachar(Chave chave, List<String> mensagens) {
        try {
            tarefas.submit(() -> {
                try {
                    analisar(chave, mensagens);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Anotar é trabalho de fundo: falhar aqui não pode estragar a conversa.
                    log.warn("Anotador falhou: {}", e.getClass().getSimpleName());
                } finally {
                    encerrar(chave);
                }
            });
        } catch (RuntimeException e) {
            encerrar(chave);
            throw e;
        }
    }

    private synchronized void encerrar(Chave chave) {
        emAnalise.remove(chave);
        notifyAll();
    }

    /**
     * Espera as análises em curso. Mesmo motivo do {@code HistoricoEmArquivo.aguardar}: o trabalho
     * sai numa thread virtual, e quem precisa conferir o resultado tem de poder juntar-se a ela.
     * Devolve falso se o prazo acabou antes.
     */
    public synchronized boolean aguardar(Duration prazo) throws InterruptedException {
        long limite = System.nanoTime() + prazo.toNanos();
        while (!emAnalise.isEmpty()) {
            long restante = limite - System.nanoTime();
            if (restante <= 0) return false;
            wait(Math.max(1, restante / 1_000_000));
        }
        return true;
    }

    /**
     * Uma chamada ao Claude por trecho, sem ferramentas e com um system próprio curto: a
     * personalidade não ajuda a extrair fato e estragaria o prefixo de cache das conversas.
     *
     * Toda recusa é contada por motivo e nada do conteúdo entra no log.
     */
    private void analisar(Chave chave, List<String> mensagens) throws IOException, InterruptedException {
        List<MemoriaService.Achado> gravados = memoria.assuntos(chave.usuario());

        ArrayNode system = json.createArrayNode();
        system.addObject().put("type", "text").put("text", SISTEMA);
        ArrayNode conversa = json.createArrayNode();
        conversa.addObject().put("role", "user").put("content", instrucao(mensagens, gravados));

        JsonNode raiz = claude.criarMensagem(system, conversa, json.createArrayNode(), MAX_TOKENS, PRAZO);
        List<JsonNode> candidatos = candidatos(raiz);

        Map<String, Integer> motivos = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        List<String> conteudos = new ArrayList<>();
        for (JsonNode candidato : candidatos) {
            String motivo = gravar(chave, mensagens, candidato, ids, conteudos);
            if (motivo != null) {
                motivos.merge(motivo, 1, Integer::sum);
            }
        }
        log.info("Anotador: {} candidatos, {} gravados, recusados {}", candidatos.size(), ids.size(), motivos);

        if (!ids.isEmpty()) {
            synchronized (this) {
                avisos.put(chave.chat(), new Anotado(List.copyOf(ids), List.copyOf(conteudos), relogio.instant()));
            }
        }
    }

    /** Devolve null quando gravou, ou o motivo da recusa. */
    private String gravar(Chave chave, List<String> mensagens, JsonNode candidato,
                          List<String> ids, List<String> conteudos) throws IOException {
        String categoria = Json.texto(candidato.path("categoria")).trim();
        String projeto = Json.texto(candidato.path("projeto")).trim();
        String assunto = Json.texto(candidato.path("assunto")).trim();
        String conteudo = Json.texto(candidato.path("conteudo")).trim();
        String evidencia = Json.texto(candidato.path("evidencia")).trim();

        if (!consta(evidencia, mensagens)) {
            return "sem_evidencia";
        }
        try {
            MemoriaService.Anotacao anotada = memoria.anotar(chave.usuario(), categoria, projeto,
                    assunto, conteudo, "anotador:" + chave.canal(), "anotador-" + java.util.UUID.randomUUID());
            if (anotada == null) {
                return "sem_mudanca";
            }
            ids.add(anotada.id());
            conteudos.add(anotada.conteudo());
            return null;
        } catch (MemoriaService.Conflito e) {
            return "conflito";
        } catch (IllegalArgumentException e) {
            // Aqui caem o filtro de segredo, a categoria inválida, o slug de projeto e os limites.
            // A mensagem da exceção não vai ao log: ela nomeia o campo, e o campo é do usuário.
            return "recusado_pelo_armazem";
        }
    }

    /**
     * A prova de citação: a evidência tem de aparecer literalmente em <b>uma</b> mensagem do
     * usuário deste trecho, pela mesma normalização de
     * {@code FerramentaMemoria.exigirConteudoDoUsuario}. Juntar as mensagens num texto só deixaria
     * passar evidência remontada de duas falas distintas.
     */
    private static boolean consta(String evidencia, List<String> mensagens) {
        String literal = Texto.normalizar(evidencia);
        if (literal.isBlank()) {
            return false;
        }
        for (String mensagem : mensagens) {
            if (Texto.normalizar(mensagem).contains(literal)) {
                return true;
            }
        }
        return false;
    }

    /** Resposta que não é o JSON combinado não grava nada: o anotador erra para o lado de não anotar. */
    private List<JsonNode> candidatos(JsonNode raiz) {
        List<JsonNode> lista = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        for (JsonNode bloco : raiz.path("content")) {
            if ("text".equals(Json.texto(bloco.path("type")))) {
                texto.append(Json.texto(bloco.path("text")));
            }
        }
        String bruto = texto.toString();
        int abre = bruto.indexOf('{');
        int fecha = bruto.lastIndexOf('}');
        if (abre < 0 || fecha <= abre) {
            return lista;
        }
        JsonNode arvore;
        try {
            arvore = json.readTree(bruto.substring(abre, fecha + 1));
        } catch (RuntimeException e) {
            return lista;
        }
        for (JsonNode candidato : arvore.path("candidatos")) {
            if (!candidato.isObject()) {
                continue;
            }
            lista.add(candidato);
            if (lista.size() >= MAX_CANDIDATOS) {
                break;
            }
        }
        return lista;
    }

    private String instrucao(List<String> mensagens, List<MemoriaService.Achado> gravados) {
        StringBuilder sb = new StringBuilder("Mensagens da pessoa nesta conversa:\n");
        List<String> recentes = mensagens.size() <= MAX_MENSAGENS ? mensagens
                : mensagens.subList(mensagens.size() - MAX_MENSAGENS, mensagens.size());
        for (int i = 0; i < recentes.size(); i++) {
            sb.append(i + 1).append(". ").append(recentes.get(i)).append('\n');
        }
        sb.append("\nAssuntos já gravados");
        if (gravados.isEmpty()) {
            sb.append(": nenhum.\n");
        } else {
            sb.append(" (categoria | assunto | id):\n");
            for (MemoriaService.Achado a : gravados) {
                sb.append("- ").append(a.tipo()).append(" | ").append(a.assunto());
                if (!a.projeto().isEmpty()) {
                    sb.append(" (projeto ").append(a.projeto()).append(')');
                }
                sb.append(" | ").append(a.id()).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * O aviso pendente desta conversa, consumido de uma vez: ele sai na resposta seguinte e só
     * nela. Vazio quando não há nada a avisar.
     */
    public synchronized String drenarAviso(long chatId) {
        Anotado anotado = avisos.remove(chatId);
        if (anotado == null || expirado(anotado, relogio.instant())) {
            return "";
        }
        List<String> curtos = anotado.conteudos().stream().map(AnotadorDeMemoria::curto).toList();
        if (curtos.size() == 1) {
            return "Anotei: " + curtos.getFirst() + ".";
        }
        return "Anotei " + curtos.size() + " coisas: " + String.join("; ", curtos) + ".";
    }

    /** O que foi anotado por último nesta conversa, com os ids, para um "desfaz" na sequência. */
    public synchronized Optional<Anotado> ultimoAnotado(long chatId) {
        Anotado anotado = avisos.get(chatId);
        return anotado == null || expirado(anotado, relogio.instant()) ? Optional.empty()
                : Optional.of(anotado);
    }

    private static boolean expirado(Anotado anotado, Instant agora) {
        return Duration.between(anotado.quando(), agora).compareTo(VALIDADE_AVISO) > 0;
    }

    private static String curto(String conteudo) {
        String plano = conteudo.replaceAll("\\s+", " ").trim();
        return plano.length() <= CURTO ? plano : plano.substring(0, CURTO) + "…";
    }

    private static String cortar(String mensagem) {
        return mensagem.length() <= MAX_CARACTERES_POR_MENSAGEM ? mensagem
                : mensagem.substring(0, MAX_CARACTERES_POR_MENSAGEM);
    }

    @PreDestroy
    void fechar() {
        tarefas.shutdownNow();
    }
}
