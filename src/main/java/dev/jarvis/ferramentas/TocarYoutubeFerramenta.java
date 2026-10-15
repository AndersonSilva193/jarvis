package dev.jarvis.ferramentas;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.tela.YoutubeService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Toca vídeo ou música do YouTube na HUD.
 *
 * O primeiro resultado da busca vira o card; os outros quatro vão como alternativas, para trocar
 * de faixa sem gastar outra consulta da cota.
 */
@Component
public class TocarYoutubeFerramenta extends FerramentaDeTela {

    private static final String SEM_CHAVE =
            "A YOUTUBE_API_KEY não está configurada, então não consigo procurar no YouTube. "
            + "Diga isso ao usuário.";

    private final YoutubeService youtube;

    public TocarYoutubeFerramenta(PresencaDaHud hud, JsonMapper json, YoutubeService youtube) {
        super(hud, json);
        this.youtube = youtube;
    }

    @Override
    public String nome() {
        return "tocar_youtube";
    }

    @Override
    public String descricao() {
        return "Procura no YouTube e toca na tela do Jarvis. Use quando o usuário pede para ouvir "
                + "uma música, ver um vídeo ou pôr som ambiente. Em modo 'musica' a busca fica "
                + "restrita à categoria de música, que é o que você quer para 'toca tal banda' ou "
                + "'põe um lo-fi'; use 'video' para o resto. Na consulta, escreva o que se "
                + "procuraria na busca do YouTube: nome da música e do artista, ou o assunto do vídeo.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");
        campos.putObject("consulta").put("type", "string")
                .put("description", "O que procurar, como se digitasse na busca do YouTube.");
        ObjectNode modo = campos.putObject("modo");
        modo.put("type", "string");
        modo.put("description", "'musica' restringe à categoria de música; 'video' procura em tudo. "
                + "O padrão é 'video'.");
        modo.putArray("enum").add("video").add("musica");
        esquema.putArray("required").add("consulta");
        return esquema;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        if (!youtube.configurado()) {
            return ResultadoFerramenta.texto(SEM_CHAVE);
        }
        if (telaFechada()) {
            return ResultadoFerramenta.texto(SEM_TELA);
        }
        String consulta = Json.texto(entrada.path("consulta")).trim();
        if (consulta.isEmpty()) {
            return ResultadoFerramenta.texto("Preciso saber o que procurar no YouTube.");
        }
        // Os dois modos têm o mesmo nome do tipo de card: "musica" e "video".
        String modo = "musica".equals(Json.texto(entrada.path("modo")).trim())
                ? CardTela.MUSICA : CardTela.VIDEO;

        List<YoutubeService.Video> achados;
        try {
            achados = youtube.procurar(consulta, modo);
        } catch (YoutubeService.CotaDoYoutubeException e) {
            return ResultadoFerramenta.texto(
                    "A cota diária do YouTube acabou; só volta amanhã. Diga isso ao usuário.");
        } catch (java.io.IOException e) {
            return ResultadoFerramenta.texto("A busca no YouTube falhou (" + e.getMessage() + ").");
        }
        if (achados.isEmpty()) {
            return ResultadoFerramenta.texto("Não achei nada no YouTube para '" + consulta + "'.");
        }

        YoutubeService.Video primeiro = achados.getFirst();
        List<DadosDoCard.Midia.Alternativa> alternativas = achados.stream()
                .skip(1)
                .map(v -> new DadosDoCard.Midia.Alternativa(v.videoId(), v.titulo(), v.canal(),
                        v.miniatura()))
                .toList();
        CardTela card = CardTela.agora(modo, primeiro.titulo(),
                new DadosDoCard.Midia(modo, primeiro.videoId(), primeiro.titulo(), primeiro.canal(),
                        primeiro.miniatura(), alternativas),
                origem(progresso));
        return ResultadoFerramenta.comCard(
                "Tocando '" + primeiro.titulo() + "'" + (primeiro.canal().isEmpty() ? "" : ", do canal "
                        + primeiro.canal()) + " (id " + card.id() + "). Diga só uma frase curta.",
                card);
    }
}
