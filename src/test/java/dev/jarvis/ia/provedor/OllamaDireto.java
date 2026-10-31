package dev.jarvis.ia.provedor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * O Ollama desta máquina chamado direto, com o corpo que o agente do PC monta para o {@code inferir}
 * (o {@code dados} do {@link ConversorOllama} mais modelo, {@code stream}, {@code num_ctx},
 * temperatura presa em 0..1 e {@code keep_alive}). Só para o benchmark rodar fora da VPS: falta o
 * salto do E2, de milissegundos, e por isso a primeira peça aqui sai um pouco antes do que no PC real.
 */
public final class OllamaDireto implements ProvedorDeModelo {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final JsonMapper json;
    private final URI url;
    private final String modelo;
    private final int contexto;

    public OllamaDireto(JsonMapper json, String base, String modelo, int contexto) {
        this.json = json;
        this.url = URI.create(base.replaceAll("/+$", "") + "/api/chat");
        this.modelo = modelo;
        this.contexto = contexto;
    }

    @Override
    public RespostaDoModelo responder(PedidoAoModelo pedido) {
        throw new UnsupportedOperationException("o benchmark só transmite");
    }

    @Override
    public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto, Cancelamento cancelamento)
            throws IOException, InterruptedException {
        ObjectNode corpo = new ConversorOllama(json).pedido(pedido, false);
        corpo.put("model", modelo).put("stream", true).put("keep_alive", "20m");
        ObjectNode opcoes = corpo.putObject("options").put("num_ctx", contexto);
        Double temperatura = pedido.opcoes().temperatura();
        if (temperatura != null) opcoes.put("temperature", Math.max(0, Math.min(1, temperatura)));
        HttpRequest requisicao = HttpRequest.newBuilder(url).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(corpo))).build();
        HttpResponse<InputStream> resposta = http.send(requisicao, HttpResponse.BodyHandlers.ofInputStream());
        if (resposta.statusCode() != 200) {
            resposta.body().close();
            throw new FalhaLocal("ollama_offline", "HTTP " + resposta.statusCode() + " do Ollama");
        }
        StringBuilder texto = new StringBuilder();
        RespostaDoModelo.MotivoDeParada motivo = RespostaDoModelo.MotivoDeParada.OUTRO;
        long entrada = 0;
        long saida = 0;
        try (InputStream corpoDaResposta = resposta.body();
                BufferedReader linhas = new BufferedReader(new InputStreamReader(corpoDaResposta, StandardCharsets.UTF_8))) {
            // Fechar o stream é o que faz o Ollama parar de gerar e a leitura bloqueada sair.
            cancelamento.aoCancelar(() -> {
                try {
                    corpoDaResposta.close();
                } catch (IOException e) {
                    // já fechado
                }
            });
            String linha;
            while ((linha = linhas.readLine()) != null) {
                if (linha.isBlank()) continue;
                JsonNode no = json.readTree(linha);
                String pedaco = no.path("message").path("content").asString("");
                if (!pedaco.isEmpty()) {
                    texto.append(pedaco);
                    aoTexto.accept(pedaco);
                }
                if (no.path("done").asBoolean(false)) {
                    motivo = "length".equals(no.path("done_reason").asString(""))
                            ? RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS : RespostaDoModelo.MotivoDeParada.FIM;
                    entrada = no.path("prompt_eval_count").asLong(0);
                    saida = no.path("eval_count").asLong(0);
                    break;
                }
            }
        } catch (IOException e) {
            if (cancelamento.cancelado()) throw new IOException("stream do Ollama fechado: " + cancelamento.motivo());
            throw e;
        }
        return new RespostaDoModelo(List.of(new Bloco.Texto(texto.toString())), motivo,
                new RespostaDoModelo.Uso(entrada, saida, 0, 0, 0));
    }
}
