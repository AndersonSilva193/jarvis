package dev.jarvis.ferramentas;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.EscutaResposta;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OuvirRespostaFerramenta implements Ferramenta {
    private final JsonMapper json;

    public OuvirRespostaFerramenta(JsonMapper json) {
        this.json = json;
    }

    @Override public String nome() { return "ouvir_resposta"; }

    @Override public String descricao() {
        return "Use quando a sua resposta terminar com uma pergunta ao usuário e você precisar da resposta dele para continuar. "
                + "Faça a pergunta no texto da resposta; esta ferramenta só abre o microfone da tela depois que a sua fala terminar. "
                + "Não use para perguntas retóricas nem no Telegram.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        ObjectNode propriedades = esquema.putObject("properties");
        propriedades.putObject("tempo").put("type", "integer").put("minimum", 5)
                .put("maximum", 20).put("default", 8)
                .put("description", "Tempo de escuta em segundos depois da fala.");
        propriedades.putObject("opcoes").put("type", "array")
                .put("description", "Respostas esperadas, quando houver.")
                .putObject("items").put("type", "string");
        propriedades.putObject("motivo").put("type", "string")
                .put("description", "Motivo curto, por exemplo: confirmar o horário.");
        esquema.putArray("required").add("motivo");
        return esquema;
    }

    @Override public String progresso() { return ""; }

    @Override public String executar(JsonNode entrada) {
        return executarDetalhado(entrada, new OuvinteProgresso() { }).texto();
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) {
        String motivo = Json.texto(entrada.path("motivo")).trim();
        if (motivo.isEmpty()) throw new IllegalArgumentException("o motivo da escuta está vazio");
        JsonNode tempo = entrada.path("tempo");
        int segundos = tempo.isNumber() ? (int) Math.clamp(Json.numero(tempo), 5L, 20L) : 8;
        List<String> opcoes = new ArrayList<>();
        JsonNode esperadas = entrada.path("opcoes");
        if (esperadas.isArray()) {
            for (JsonNode opcao : esperadas) {
                String texto = Json.texto(opcao).trim();
                if (!texto.isEmpty()) opcoes.add(texto);
            }
        }
        return new ResultadoFerramenta("Escuta marcada para depois da fala.", List.of(), List.of(),
                null, List.of(), List.of(), new EscutaResposta(segundos, opcoes, motivo));
    }
}
