package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.BriefingService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Entrega o briefing do dia inteiro de uma vez, para pedidos como "me dá o briefing". */
@Component
public class BriefingFerramenta implements Ferramenta {

    private final BriefingService briefing;
    private final JsonMapper json;

    public BriefingFerramenta(BriefingService briefing, JsonMapper json) {
        this.briefing = briefing;
        this.json = json;
    }

    @Override
    public String nome() {
        return "briefing_do_dia";
    }

    @Override
    public String descricao() {
        return "Monta o briefing completo do dia de uma vez: clima, lembretes de hoje, situação dos "
                + "sites e do servidor e as principais notícias. Use para pedidos amplos como "
                + "'me dá o briefing', 'resumo do dia', 'como está tudo' ou 'novidades'. "
                + "Para um assunto específico, prefira a ferramenta daquele assunto.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    @Override
    public String executar(JsonNode entrada) {
        return briefing.deHoje().paraTexto();
    }
}
