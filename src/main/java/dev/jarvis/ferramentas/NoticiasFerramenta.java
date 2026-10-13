package dev.jarvis.ferramentas;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.NoticiasService;
import dev.jarvis.briefing.fontes.NoticiasService.Noticia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Conta as novidades dos feeds configurados, por tema, nas últimas 24 horas. */
@Component
public class NoticiasFerramenta implements Ferramenta {

    /** Quantas manchetes por pergunta: o suficiente para dar o panorama sem torrar tokens. */
    private static final int LIMITE = 5;
    private static final DateTimeFormatter EXIBICAO = DateTimeFormatter.ofPattern("dd/MM HH:mm");

    private final NoticiasService noticias;
    private final JsonMapper json;
    private final ZoneId zona;

    public NoticiasFerramenta(NoticiasService noticias, JsonMapper json, JarvisProperties props) {
        this.noticias = noticias;
        this.json = json;
        this.zona = props.zona();
    }

    @Override
    public String nome() {
        return "noticias";
    }

    @Override
    public String descricao() {
        Set<String> temas = noticias.temas();
        return "Mostra as principais notícias das últimas 24 horas nos feeds acompanhados, com "
                + "título, fonte, horário e um resumo. Temas disponíveis: "
                + (temas.isEmpty() ? "nenhum configurado" : temas)
                + ". Use tema='" + NoticiasService.TODOS + "' para ver de todos os temas juntos.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode tema = esquema.putObject("properties").putObject("tema");
        tema.put("type", "string");
        tema.put("description", "Tema das notícias, ou '" + NoticiasService.TODOS + "'.");
        var opcoes = tema.putArray("enum").add(NoticiasService.TODOS);
        noticias.temas().forEach(opcoes::add);
        esquema.putArray("required").add("tema");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        if (noticias.temas().isEmpty()) {
            return "Nenhum feed configurado. Adicione em NOTICIAS_FEEDS no arquivo .env, "
                    + "no formato tema|url,tema|url.";
        }
        String tema = Json.texto(entrada.path("tema")).trim();
        if (tema.isEmpty()) {
            tema = NoticiasService.TODOS;
        }
        if (!tema.equals(NoticiasService.TODOS) && !noticias.temas().contains(tema)) {
            return "Tema '" + tema + "' não está configurado. Disponíveis: " + noticias.temas() + ".";
        }

        List<Noticia> ultimas = noticias.ultimas(tema, LIMITE);
        if (ultimas.isEmpty()) {
            return "Nenhuma notícia nas últimas 24 horas para o tema '" + tema + "'.";
        }

        StringBuilder sb = new StringBuilder();
        for (Noticia n : ultimas) {
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append("[").append(n.tema()).append("] ")
                    .append(n.publicadoEm().withZoneSameInstant(zona).format(EXIBICAO));
            if (!n.fonte().isBlank()) {
                sb.append(" · ").append(n.fonte());
            }
            sb.append("\n").append(n.titulo());
            if (!n.resumo().isBlank()) {
                sb.append("\n").append(n.resumo());
            }
            if (!n.link().isBlank()) {
                sb.append("\n").append(n.link());
            }
        }
        return sb.toString();
    }
}
