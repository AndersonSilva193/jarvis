package dev.jarvis.voz;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Text-to-speech da ElevenLabs, em duas chamadas HTTP (https://elevenlabs.io/docs/api-reference).
 *
 * O formato é mp3 de 22 kHz e 32 kbps: dá uns 4 KB por segundo de fala, então o cache de 200 MB
 * guarda uma noite inteira de briefings e o áudio começa a tocar rápido mesmo na VPS.
 *
 * Nada de chave no log — nem na URL, nem no corpo. Só método, status e motivo.
 */
@Component
public class ElevenLabsVoz implements ProvedorVoz {

    private static final Logger log = LoggerFactory.getLogger(ElevenLabsVoz.class);
    private static final String BASE = "https://api.elevenlabs.io/v1";
    private static final String FORMATO = "mp3_22050_32";
    private static final Duration ESPERA_SINTESE = Duration.ofSeconds(30);
    private static final Duration ESPERA_SALDO = Duration.ofSeconds(15);

    private final HttpClient http;
    private final JsonMapper json;
    private final JarvisProperties props;

    public ElevenLabsVoz(HttpClient http, JsonMapper json, JarvisProperties props) {
        this.http = http;
        this.json = json;
        this.props = props;
    }

    @Override
    public String nome() {
        return "elevenlabs";
    }

    @Override
    public byte[] sintetizar(String texto) throws CotaDeVozException, IOException, InterruptedException {
        ObjectNode corpo = json.createObjectNode();
        corpo.put("text", texto);
        corpo.put("model_id", props.modeloDeVoz());
        // Fixar o idioma evita o modelo "adivinhar" inglês num nome próprio e trocar o sotaque.
        corpo.put("language_code", "pt");

        String vozId = URLEncoder.encode(props.voz().vozId(), StandardCharsets.UTF_8);
        HttpRequest requisicao = HttpRequest.newBuilder(
                        URI.create(BASE + "/text-to-speech/" + vozId + "?output_format=" + FORMATO))
                .timeout(ESPERA_SINTESE)
                .header("xi-api-key", props.voz().apiKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo)))
                .build();

        HttpResponse<byte[]> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
        if (resposta.statusCode() == 200) {
            return resposta.body();
        }
        throw recusa("text-to-speech", resposta.statusCode(), resposta.body());
    }

    /** Lê o saldo da assinatura: caracteres usados, limite do período e quando a contagem zera. */
    public SaldoDeVoz consultarSaldo() throws CotaDeVozException, IOException, InterruptedException {
        HttpRequest requisicao = HttpRequest.newBuilder(URI.create(BASE + "/user/subscription"))
                .timeout(ESPERA_SALDO)
                .header("xi-api-key", props.voz().apiKey())
                .GET()
                .build();

        HttpResponse<byte[]> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofByteArray());
        if (resposta.statusCode() != 200) {
            throw recusa("user/subscription", resposta.statusCode(), resposta.body());
        }
        return interpretar(json.readTree(new String(resposta.body(), StandardCharsets.UTF_8)));
    }

    /**
     * Converte a resposta de /v1/user/subscription no saldo.
     *
     * Separado e estático para o teste exercitar o parse com um JSON fixo, sem rede — o mesmo
     * arranjo do ClimaService.
     */
    static SaldoDeVoz interpretar(JsonNode raiz) throws IOException {
        if (!raiz.path("character_limit").isNumber()) {
            throw new IOException("resposta da ElevenLabs sem character_limit");
        }
        long usados = Json.numero(raiz.path("character_count"));
        long limite = Json.numero(raiz.path("character_limit"));
        JsonNode renovacao = raiz.path("next_character_count_reset_unix");
        return new SaldoDeVoz(usados, limite,
                renovacao.isNumber() ? java.time.Instant.ofEpochSecond(renovacao.longValue()) : null);
    }

    /**
     * Traduz a recusa em algo acionável.
     *
     * A cota estourada chega como 401 com detail.status = "quota_exceeded" — o mesmo código de
     * chave inválida. Sem ler o corpo, o Jarvis não saberia se precisa esperar a renovação ou se
     * alguém errou a chave no .env.
     */
    private CotaDeVozException recusa(String chamada, int status, byte[] corpo) throws IOException {
        String detalhe = "";
        try {
            detalhe = Json.texto(json.readTree(new String(corpo, StandardCharsets.UTF_8))
                    .path("detail").path("status"));
        } catch (RuntimeException e) {
            // Corpo que não é JSON (página de erro do proxy, por exemplo): fica só com o status.
        }
        String motivo = switch (status) {
            case 429 -> "limite";
            case 401, 403 -> "quota_exceeded".equals(detalhe) ? "cota" : "chave";
            default -> null;
        };
        if (motivo == null) {
            log.warn("ElevenLabs recusou {} com HTTP {}", chamada, status);
            throw new IOException("ElevenLabs respondeu HTTP " + status + " em " + chamada);
        }
        log.warn("ElevenLabs recusou {} com HTTP {} ({})", chamada, status, motivo);
        return new CotaDeVozException(motivo, "ElevenLabs recusou " + chamada + ": HTTP " + status);
    }
}
