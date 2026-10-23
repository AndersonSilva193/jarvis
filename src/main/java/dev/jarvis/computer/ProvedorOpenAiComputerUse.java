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

/** Responses API: previous_response_id e call_id ficam neste adaptador. */
public final class ProvedorOpenAiComputerUse implements ProvedorComputerUse {
    private final HttpClient http;
    private final JsonMapper json;
    private final String chave;
    private final String modelo;
    private final MedidorDeCusto medidor;
    private String respostaAnterior;
    private String chamadaAnterior;
    private String instrucoes;

    public ProvedorOpenAiComputerUse(HttpClient http, JsonMapper json, JarvisProperties props,
            MedidorDeCusto medidor) {
        this.http = http; this.json = json;
        this.chave = props.computerUse().openaiApiKey();
        this.modelo = props.computerUse().modeloOpenai();
        this.medidor = medidor;
    }
    @Override public String nome() { return "openai"; }
    @Override public String modelo() { return modelo; }
    @Override public Passo iniciar(String objetivo, byte[] capturaInicial, ArrayNode sistema,
            Duration restante) throws Exception {
        if (chave == null || chave.isBlank()) throw new IllegalStateException("OPENAI_API_KEY ausente");
        ObjectNode corpo = base();
        StringBuilder instrucoes = new StringBuilder();
        for (JsonNode bloco : sistema) instrucoes.append(Json.texto(bloco.path("text"))).append('\n');
        this.instrucoes = instrucoes.toString();
        corpo.put("instructions", this.instrucoes);
        var conteudo = corpo.putArray("input").addObject().put("role", "user").putArray("content");
        conteudo.addObject().put("type", "input_text").put("text", objetivo);
        conteudo.addObject().put("type", "input_image").put("image_url",
                "data:image/png;base64," + Base64.getEncoder().encodeToString(capturaInicial));
        return chamar(corpo, restante);
    }
    @Override public Passo continuar(List<Resultado> resultados, Duration restante) throws Exception {
        if (resultados.isEmpty() || chamadaAnterior == null)
            throw new IllegalStateException("nenhuma chamada computer pendente");
        Resultado ultima = resultados.getLast();
        if (ultima.erro() || ultima.imagem() == null)
            throw new IllegalStateException("acao do PC falhou");
        ObjectNode corpo = base().put("previous_response_id", respostaAnterior)
                .put("instructions", instrucoes);
        corpo.putArray("input").addObject().put("type", "computer_call_output")
                .put("call_id", chamadaAnterior).putObject("output")
                .put("type", "computer_screenshot")
                .put("image_url", "data:image/png;base64," +
                        Base64.getEncoder().encodeToString(ultima.imagem()))
                .put("detail", "original");
        return chamar(corpo, restante);
    }
    private ObjectNode base() {
        ObjectNode corpo = json.createObjectNode().put("model", modelo);
        corpo.putArray("tools").addObject().put("type", "computer");
        return corpo;
    }
    private Passo chamar(ObjectNode corpo, Duration restante) throws Exception {
        HttpRequest pedido = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
                .timeout(restante.compareTo(Duration.ofSeconds(90)) < 0 ? restante : Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + chave)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
        HttpResponse<String> resposta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        if (resposta.statusCode() != 200)
            throw new IllegalStateException("OpenAI computer use HTTP " + resposta.statusCode());
        JsonNode raiz = json.readTree(resposta.body());
        respostaAnterior = Json.texto(raiz.path("id"));
        // Como no provedor da Anthropic: o custo entra aqui, e o TarefasComputerUse segue só com a
        // soma que ele mostra na HUD. A Responses API não tem campos de cache, então os dois vão
        // em zero — não é o cache falhando, é o provedor não informar.
        JsonNode usage = raiz.path("usage");
        medidor.registrar(Origem.COMPUTER_USE, modelo,
                Json.numero(usage.path("input_tokens")),
                Json.numero(usage.path("output_tokens")), 0, 0, 0);
        Passo passo = interpretarResposta(raiz);
        if (!passo.chamadas().isEmpty()) chamadaAnterior = passo.chamadas().getFirst().id();
        return passo;
    }
    static Passo interpretarResposta(JsonNode raiz) {
        List<Chamada> chamadas = new ArrayList<>();
        StringBuilder texto = new StringBuilder();
        for (JsonNode bloco : raiz.path("output")) {
            String tipo = Json.texto(bloco.path("type"));
            if ("computer_call".equals(tipo)) {
                if (!chamadas.isEmpty()) throw new IllegalStateException("chamadas computer simultaneas");
                if (bloco.path("pending_safety_checks").isArray()
                        && !bloco.path("pending_safety_checks").isEmpty())
                    throw new IllegalStateException("verificacao de seguranca do provedor pendente");
                String id = Json.texto(bloco.path("call_id"));
                for (JsonNode acao : bloco.path("actions"))
                    chamadas.add(new Chamada(id, Json.texto(acao.path("type")), acao));
            } else if ("message".equals(tipo)) {
                for (JsonNode parte : bloco.path("content"))
                    if ("output_text".equals(Json.texto(parte.path("type"))))
                        texto.append(Json.texto(parte.path("text")));
            }
        }
        return new Passo(chamadas, texto.toString(),
                Json.numero(raiz.path("usage").path("input_tokens")),
                Json.numero(raiz.path("usage").path("output_tokens")));
    }
}
