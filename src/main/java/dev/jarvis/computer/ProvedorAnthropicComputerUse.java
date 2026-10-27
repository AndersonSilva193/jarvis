package dev.jarvis.computer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Messages API: guarda os blocos tool_use e devolve tool_result por ID. */
public final class ProvedorAnthropicComputerUse implements ProvedorComputerUse {
    private final HttpClient http;
    private final JsonMapper json;
    private final String chave;
    private final String modelo;
    private final String ttlDeCache;
    private final MedidorDeCusto medidor;
    private final ArrayNode mensagens;
    private ArrayNode sistema;

    public ProvedorAnthropicComputerUse(HttpClient http, JsonMapper json, JarvisProperties props,
            MedidorDeCusto medidor) {
        this.http = http; this.json = json;
        this.chave = props.anthropic().apiKey();
        this.modelo = props.computerUse().modeloAnthropic();
        this.ttlDeCache = props.anthropic().ttlDeCache();
        this.medidor = medidor;
        this.mensagens = json.createArrayNode();
    }
    @Override public String nome() { return "anthropic"; }
    @Override public String modelo() { return modelo; }
    @Override public Passo iniciar(String objetivo, byte[] capturaInicial, ArrayNode sistema,
            Duration restante) throws Exception {
        this.sistema = sistema;
        ArrayNode conteudo = json.createArrayNode();
        conteudo.addObject().put("type", "text").put("text", objetivo);
        conteudo.addObject().put("type", "image").putObject("source")
                .put("type", "base64").put("media_type", "image/png")
                .put("data", Base64.getEncoder().encodeToString(capturaInicial));
        mensagens.addObject().put("role", "user").set("content", conteudo);
        return chamar(restante);
    }
    @Override public Passo continuar(List<Resultado> resultados, Duration restante) throws Exception {
        ArrayNode blocos = json.createArrayNode();
        for (Resultado r : resultados) {
            ObjectNode bloco = blocos.addObject().put("type", "tool_result")
                    .put("tool_use_id", r.id()).put("toolset_name", "computer");
            if (r.erro()) {
                bloco.put("is_error", true).put("content", r.texto());
            } else if ("screenshot".equals(r.texto()) && r.imagem() != null) {
                ArrayNode conteudo = bloco.putArray("content");
                conteudo.addObject().put("type", "image").putObject("source")
                        .put("type", "base64").put("media_type", "image/png")
                        .put("data", Base64.getEncoder().encodeToString(r.imagem()));
            } else bloco.put("content", r.texto() == null ? "ok" : r.texto());
        }
        mensagens.addObject().put("role", "user").set("content", blocos);
        return chamar(restante);
    }
    private Passo chamar(Duration restante) throws Exception {
        ObjectNode corpo = json.createObjectNode().put("model", modelo).put("max_tokens", 2048);
        corpo.set("system", sistema); corpo.set("messages", mensagens);
        ObjectNode ferramenta = corpo.putArray("tools").addObject().put("type", "computer_toolset_20260801");
        ObjectNode configs = ferramenta.putObject("configs");
        for (String nome : List.of("zoom", "triple_click", "middle_click", "left_mouse_down",
                "left_mouse_up", "hold_key", "cursor_position"))
            configs.putObject(nome).put("enabled", false);
        corpo.putObject("tool_choice").put("type", "auto").put("disable_parallel_tool_use", true);
        // Cache automático da conversa, como no laço do Assistente — e aqui pesa mais: uma tarefa
        // visual reenvia a lista inteira de mensagens, com uma captura de tela por ciclo, por até
        // COMPUTER_USE_MAX_CICLOS ciclos. Do segundo em diante, as imagens já mandadas voltam como
        // leitura de cache. O TTL é o mesmo do marcador do bloco fixo do system, de propósito.
        ObjectNode controle = corpo.putObject("cache_control").put("type", "ephemeral");
        if ("1h".equals(ttlDeCache)) controle.put("ttl", "1h");
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(restante.compareTo(Duration.ofSeconds(90)) < 0 ? restante : Duration.ofSeconds(90))
                .header("x-api-key", chave)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200)
            throw new IllegalStateException("Anthropic computer use HTTP " + resposta.statusCode());
        JsonNode raiz = json.readTree(resposta.body());
        ArrayNode conteudo = (ArrayNode) raiz.path("content");
        mensagens.addObject().put("role", "assistant").set("content", conteudo);
        // O custo é registrado aqui, na instância, e não no TarefasComputerUse: ele soma os mesmos
        // tokens para o Retrato da HUD, e registrar nos dois lugares dobraria a conta.
        JsonNode usage = raiz.path("usage");
        medidor.registrar(Origem.COMPUTER_USE, modelo,
                Json.numero(usage.path("input_tokens")),
                Json.numero(usage.path("output_tokens")),
                Json.numero(usage.path("cache_creation_input_tokens")),
                Json.numero(usage.path("cache_read_input_tokens")), 0);
        return interpretarResposta(raiz);
    }
    static Passo interpretarResposta(JsonNode raiz) {
        JsonNode conteudo = raiz.path("content");
        List<Chamada> chamadas = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        for (JsonNode bloco : conteudo) {
            if ("tool_use".equals(Json.texto(bloco.path("type")))) {
                if (!"computer".equals(Json.texto(bloco.path("toolset_name"))))
                    throw new IllegalStateException("ferramenta inesperada do provedor");
                chamadas.add(new Chamada(Json.texto(bloco.path("id")),
                        Json.texto(bloco.path("name")), bloco.path("input")));
            } else if ("text".equals(Json.texto(bloco.path("type"))))
                texto.append(Json.texto(bloco.path("text")));
        }
        return new Passo(chamadas, texto.toString(), Json.numero(raiz.path("usage").path("input_tokens")),
                Json.numero(raiz.path("usage").path("output_tokens")));
    }
}
