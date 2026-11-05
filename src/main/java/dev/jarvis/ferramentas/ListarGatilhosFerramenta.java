package dev.jarvis.ferramentas;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.gatilhos.Gatilhos;
import dev.jarvis.gatilhos.RegraDeGatilho;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** "Quais gatilhos eu deixei ligados?": a lista, curta, com id para o pausar e o apagar. */
@Component
public class ListarGatilhosFerramenta extends FerramentaDeGatilho {

    public ListarGatilhosFerramenta(Gatilhos gatilhos, JsonMapper json, JarvisProperties props) {
        super(gatilhos, json, props);
    }

    @Override public String nome() { return "listar_gatilhos"; }

    @Override public String descricao() {
        return "Lista os gatilhos (regras 'quando X acontecer, faça Y') do dono: id, evento, o que fazer, "
                + "se está pausado e quando disparou pela última vez. Não cite o id ao dono.";
    }

    @Override public ObjectNode esquemaEntrada() {
        var n = json.createObjectNode().put("type", "object");
        n.putObject("properties");
        return n;
    }

    @Override protected String responder(JsonNode entrada, long usuario) {
        List<RegraDeGatilho> regras = gatilhos.listar(usuario);
        if (regras.isEmpty()) return "Não há gatilho nenhum.";
        return regras.size() + (regras.size() == 1 ? " gatilho:\n" : " gatilhos:\n")
                + String.join("\n", regras.stream().map(this::linha).toList());
    }

    @Override public String progresso() { return "consultando os gatilhos"; }
}
