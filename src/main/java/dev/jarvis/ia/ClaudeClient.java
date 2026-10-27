package dev.jarvis.ia;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Cliente mínimo da Messages API do Claude (https://platform.claude.com/docs).
 * Uma chamada = manda o histórico + a lista de ferramentas e recebe a próxima resposta do modelo.
 */
@Component
public class ClaudeClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeClient.class);
    private static final URI URL = URI.create("https://api.anthropic.com/v1/messages");
    /** Erros temporários (limite de taxa, sobrecarga) que valem uma nova tentativa. */
    private static final Set<Integer> STATUS_TEMPORARIOS = Set.of(429, 500, 502, 503, 529);
    private static final int TENTATIVAS = 3;
    /** Prazo de uma conversa normal: o modelo pode pensar e chamar ferramenta antes de responder. */
    private static final Duration PRAZO_PADRAO = Duration.ofSeconds(120);

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties.Anthropic config;
    private final MedidorDeCusto medidor;
    /** O esforço de cada perfil, resolvido na subida e nunca mais. Vazio = nenhum é emitido. */
    private final Map<PerfilDeEsforco, Esforco> esforcos;

    public ClaudeClient(HttpClient http, JsonMapper json, JarvisProperties props,
            MedidorDeCusto medidor) {
        this.http = http;
        this.json = json;
        this.config = props.anthropic();
        this.medidor = medidor;
        this.esforcos = resolverEsforcos(this.config);
    }

    /**
     * Lê os cinco níveis configurados <b>uma vez, no construtor</b> — e é isso que cumpre "o
     * esforço é fixo dentro da mesma conversa" por construção: nada relê a configuração em tempo
     * de pedido, diferente do {@code PersonalidadeService}.
     *
     * <p>É também o único lugar onde o WARN de valor torto sai: na subida, nomeando a variável e
     * o valor recusado. A cada pedido ele seria ruído, e o nível já não mudaria mesmo.
     *
     * <p>Sem o bloco {@code esforcos} nenhum nível é resolvido, e o corpo do pedido fica igual ao
     * de antes desta configuração existir. Campo em branco é o padrão do perfil, sem WARN: branco
     * é "não mexi nisso", e só texto que não casa nível nenhum é erro de quem escreveu.
     */
    private static Map<PerfilDeEsforco, Esforco> resolverEsforcos(JarvisProperties.Anthropic config) {
        JarvisProperties.Anthropic.Esforcos configurados =
                config == null ? null : config.esforcos();
        if (configurados == null) {
            return Map.of();
        }
        Map<PerfilDeEsforco, Esforco> resolvidos = new EnumMap<>(PerfilDeEsforco.class);
        for (PerfilDeEsforco perfil : PerfilDeEsforco.values()) {
            String bruto = brutoDe(configurados, perfil);
            Esforco esforco = Esforco.porChave(bruto);
            if (esforco == null) {
                if (bruto != null && !bruto.isBlank()) {
                    log.warn("{}: '{}' não é um esforço válido (low, medium ou high); seguindo com "
                            + "{}", perfil.variavel(), bruto.trim(), perfil.padrao().chave());
                }
                esforco = perfil.padrao();
            }
            resolvidos.put(perfil, esforco);
        }
        return Map.copyOf(resolvidos);
    }

    private static String brutoDe(JarvisProperties.Anthropic.Esforcos esforcos,
            PerfilDeEsforco perfil) {
        return switch (perfil) {
            case CONVERSA -> esforcos.conversa();
            case BRIEFING -> esforcos.briefing();
            case INICIALIZACAO -> esforcos.inicializacao();
            case APOIO -> esforcos.apoio();
            case TAREFA -> esforcos.tarefa();
        };
    }

    /**
     * O perfil de esforço de quem não o informou — e a origem basta para quase todos.
     *
     * <p>Irmão do {@link #cacheDaConversa(Origem)}, e pelo mesmo motivo: a decisão é uma
     * propriedade do caminho, não do pedido. A exceção é a tarefa agendada, que gasta na
     * {@code CONVERSA} e pensa com {@code ESFORCO_TAREFA} — ela informa o perfil.
     *
     * <p>O {@code switch} é exaustivo de propósito: origem nova tem de escolher um perfil, não
     * herdar um por omissão.
     */
    private static PerfilDeEsforco perfilPadrao(Origem origem) {
        return switch (origem) {
            // O computer use monta o corpo por conta própria e nunca chega aqui.
            case CONVERSA, COMPUTER_USE -> PerfilDeEsforco.CONVERSA;
            case BRIEFING -> PerfilDeEsforco.BRIEFING;
            case INICIALIZACAO -> PerfilDeEsforco.INICIALIZACAO;
            case ANOTADOR, CLASSIFICADOR -> PerfilDeEsforco.APOIO;
        };
    }

    /** Versão simples, com o system num bloco de texto só e sem cache. */
    public JsonNode criarMensagem(Origem origem, String system, ArrayNode mensagens,
            ArrayNode ferramentas) throws IOException, InterruptedException {
        ArrayNode blocos = json.createArrayNode();
        blocos.addObject().put("type", "text").put("text", system);
        return criarMensagem(origem, blocos, mensagens, ferramentas);
    }

    /**
     * Versão com o system em blocos, que é o que permite marcar o bloco fixo com
     * {@code cache_control} e pagar o texto da personalidade uma vez só.
     */
    public JsonNode criarMensagem(Origem origem, ArrayNode system, ArrayNode mensagens,
            ArrayNode ferramentas) throws IOException, InterruptedException {
        return criarMensagem(origem, perfilPadrao(origem), system, mensagens, ferramentas);
    }

    /**
     * A mesma chamada, com o perfil de esforço dito em voz alta.
     *
     * <p>Existe para o único caso em que a {@link Origem} não decide: a tarefa agendada gasta na
     * {@code CONVERSA} e pensa com o {@code ESFORCO_TAREFA}. Quem informa é o {@code Assistente},
     * que é quem conhece o {@link Canal}.
     */
    public JsonNode criarMensagem(Origem origem, PerfilDeEsforco perfil, ArrayNode system,
            ArrayNode mensagens, ArrayNode ferramentas) throws IOException, InterruptedException {
        return criarMensagem(origem, perfil, system, mensagens, ferramentas, config.maxTokens(),
                PRAZO_PADRAO, TENTATIVAS, cacheDaConversa(origem));
    }

    /**
     * Se esta chamada leva o <b>cache automático da conversa</b>: um {@code cache_control} no nível
     * do pedido, que a API põe no último bloco cacheável e avança sozinha conforme as mensagens
     * crescem.
     *
     * <p>Quem decide é a {@link Origem}, e ela decide bem porque a pergunta é exatamente "este
     * caminho chama o modelo <b>várias vezes com o mesmo prefixo</b>?". A {@code CONVERSA} é o laço
     * de ferramentas do {@code Assistente}, de até oito passos: do segundo em diante, tudo que já
     * foi enviado volta como leitura de cache (0,1× do preço) em vez de entrada cheia.
     *
     * <p>As outras fazem <b>uma</b> chamada só — briefing, introdução da HUD, classificador de
     * resposta, anotador de memória — e para elas a escrita do cache seria prêmio de 1,25× sem
     * nenhuma leitura depois. Origem nova que venha a repetir chamada no mesmo prefixo entra aqui.
     *
     * <p>São dois breakpoints dos quatro que a API permite: este e o do bloco fixo do {@code system}.
     * Os dois levam o mesmo TTL de propósito — entrada de TTL mais longo tem de aparecer antes da de
     * TTL mais curto, e misturar seria um HTTP 400.
     */
    private static boolean cacheDaConversa(Origem origem) {
        return origem == Origem.CONVERSA;
    }

    /**
     * Chamada curta de decisão: teto de tokens e prazo próprios, tentativa única.
     *
     * <p>Sem repetição de propósito: a espera entre tentativas é de segundos e estouraria o prazo
     * curto de quem chama assim. Quem decide em poucos tokens prefere falhar rápido — e tratar a
     * falha — a segurar o turno do usuário.
     */
    public JsonNode criarMensagem(Origem origem, ArrayNode system, ArrayNode mensagens,
            ArrayNode ferramentas, int maxTokens, Duration prazo)
            throws IOException, InterruptedException {
        return criarMensagem(origem, perfilPadrao(origem), system, mensagens, ferramentas,
                maxTokens, prazo, 1, false);
    }

    private JsonNode criarMensagem(Origem origem, PerfilDeEsforco perfil, ArrayNode system,
            ArrayNode mensagens, ArrayNode ferramentas, int maxTokens, Duration prazo,
            int tentativas, boolean cacheDaConversa) throws IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("model", config.modelo());
        corpo.put("max_tokens", maxTokens);
        corpo.set("system", system);
        corpo.set("messages", mensagens);
        if (!ferramentas.isEmpty()) {
            corpo.set("tools", ferramentas);
        }
        if (cacheDaConversa) {
            ObjectNode controle = corpo.putObject("cache_control").put("type", "ephemeral");
            if ("1h".equals(config.ttlDeCache())) {
                controle.put("ttl", "1h");
            }
        }
        // Quanto este caminho pensa. O campo só sai quando há nível resolvido: sem o bloco
        // esforcos, o corpo fica byte a byte igual ao de antes desta configuração existir.
        Esforco esforco = esforcos.get(perfil);
        if (esforco != null) {
            corpo.putObject("output_config").put("effort", esforco.chave());
        }

        HttpRequest requisicao = HttpRequest.newBuilder(URL)
                .timeout(prazo)
                .header("x-api-key", config.apiKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        for (int tentativa = 1; ; tentativa++) {
            HttpResponse<String> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofString());
            int status = resposta.statusCode();
            if (status == 200) {
                JsonNode raiz = json.readTree(resposta.body());
                registrarConsumo(origem, raiz.path("usage"));
                return raiz;
            }
            if (STATUS_TEMPORARIOS.contains(status) && tentativa < tentativas) {
                log.warn("API do Claude respondeu {} (tentativa {}), tentando de novo...", status, tentativa);
                Thread.sleep(2_000L * tentativa);
                continue;
            }
            throw new ErroDaApiDoModelo(status, detalheSeguro(resposta.body()));
        }
    }

    /**
     * Mostra no log quanto a chamada consumiu e soma isso ao diário de custo. Os dois campos de
     * cache só aparecem quando há cache: {@code cache_escrito} na primeira chamada (quando o
     * prefixo é gravado) e {@code cache_lido} nas seguintes. Se as duas ficarem sempre em zero, o
     * bloco fixo não está sendo cacheado.
     *
     * <p>Este é o <b>único</b> ponto em que uma chamada à Messages API vira custo, e ele roda só
     * no HTTP 200, dentro do laço de tentativas: as repetições são de 429/500/502/503/529, que não
     * carregam {@code usage} e nunca chegam aqui. Uma chamada repetida conta uma vez.
     *
     * <p>A contagem de buscas que o {@code Assistente} faz do mesmo campo <b>não</b> é duplicação:
     * ela alimenta o teto de {@code BUSCA_WEB_MAX_USOS}, que é outro assunto. São dois leitores do
     * mesmo número, com fins diferentes.
     */
    private void registrarConsumo(Origem origem, JsonNode usage) {
        long entrada = Json.numero(usage.path("input_tokens"));
        long saida = Json.numero(usage.path("output_tokens"));
        long cacheEscrito = Json.numero(usage.path("cache_creation_input_tokens"));
        long cacheLido = Json.numero(usage.path("cache_read_input_tokens"));
        long buscas = Json.numero(usage.path("server_tool_use").path("web_search_requests"));
        log.info("Claude: entrada={} saída={} cache_escrito={} cache_lido={}",
                entrada, saida, cacheEscrito, cacheLido);
        medidor.registrar(origem, config.modelo(), entrada, saida, cacheEscrito, cacheLido, buscas);
    }

    private String detalheSeguro(String corpo) {
        try {
            JsonNode erro = json.readTree(corpo).path("error");
            String tipo = Json.texto(erro.path("type"));
            String mensagem = Json.texto(erro.path("message"));
            if (mensagem.isBlank()) return tipo.isBlank() ? "sem detalhe do provedor" : tipo;
            mensagem = mensagem.replaceAll("[\\r\\n\\p{Cntrl}]", " ")
                    .replaceAll("(?i)Bearer\\s+[^\\s]+", "Bearer [oculto]")
                    .replaceAll("(?i)sk-[a-z0-9_-]+", "[chave oculta]");
            if (mensagem.length() > 240) mensagem = mensagem.substring(0, 240) + "…";
            return (tipo.isBlank() ? "" : tipo + ": ") + mensagem;
        } catch (RuntimeException e) {
            return "resposta de erro sem detalhe legível";
        }
    }
}
