package dev.jarvis.holograma;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lê <b>uma</b> linha do NDJSON e diz o que ela é: meta, peça ou fim.
 *
 * <p>Nunca lança. Modelo pequeno escreve cerca de markdown, um "Aqui está o objeto:", um
 * {@code <think>} ou uma linha cortada no teto de tokens — e uma linha ruim não pode derrubar as
 * boas em volta. Tudo que não for um objeto JSON com {@code "t"} conhecido volta vazio, em
 * silêncio: quem conta o que ficou de fora é a peça que faltou, não um aviso por linha de lixo.
 *
 * <p>Duas tolerâncias, as duas por causa de modelo que imita array JSON: a vírgula no fim da
 * linha é tirada, e uma linha que é só {@code [} ou {@code ]} cai no "não é objeto".
 */
@Component
public class HologramaLineParser {

    private static final Pattern CONTROLE = Pattern.compile("\\p{Cntrl}");
    private static final Pattern ESPACOS = Pattern.compile("\\s+");

    private final JsonMapper json;

    public HologramaLineParser(JsonMapper json) {
        this.json = json;
    }

    public Optional<EventoHolograma> ler(String linha) {
        if (linha == null) {
            return Optional.empty();
        }
        String texto = linha.strip();
        if (texto.endsWith(",")) {
            texto = texto.substring(0, texto.length() - 1).stripTrailing();
        }
        // Cerca de markdown, texto solto e <think> não começam com chave.
        if (!texto.startsWith("{") || !texto.endsWith("}")) {
            return Optional.empty();
        }
        JsonNode no;
        try {
            no = json.readTree(texto);
        } catch (RuntimeException e) {
            // No Jackson 3 o erro de leitura é JacksonException, que é RuntimeException.
            return Optional.empty();
        }
        if (!(no instanceof ObjectNode objeto)) {
            return Optional.empty();
        }
        return switch (Json.texto(objeto.get("t")).strip().toLowerCase(Locale.ROOT)) {
            case "meta" -> Optional.of(new EventoHolograma.Meta(meta(objeto)));
            case "p" -> Optional.of(new EventoHolograma.Peca(objeto));
            case "fim" -> Optional.of(new EventoHolograma.Fim());
            default -> Optional.empty();
        };
    }

    private static HologramaMeta meta(ObjectNode objeto) {
        String nome = limpo(Json.texto(objeto.get("nome")), HologramaMeta.MAX_NOME);
        return new HologramaMeta(nome.isEmpty() ? HologramaMeta.PADRAO.nome() : nome,
                limpo(Json.texto(objeto.get("cat")), HologramaMeta.MAX_CATEGORIA),
                limpo(Json.texto(objeto.get("desc")), HologramaMeta.MAX_DESCRICAO));
    }

    /** Sem caractere de controle, espaços colapsados e cortado no teto. */
    static String limpo(String bruto, int maximo) {
        String texto = ESPACOS.matcher(CONTROLE.matcher(bruto).replaceAll(" ")).replaceAll(" ").strip();
        return texto.length() <= maximo ? texto : texto.substring(0, maximo).stripTrailing();
    }
}
