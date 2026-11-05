package dev.jarvis.ferramentas;

import java.io.IOException;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.gatilhos.Gatilhos;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Desliga um gatilho sem apagá-lo, ou religa. Sem aprovação: reduzir o que o Jarvis faz sozinho é
 * sempre seguro, e religar é a regra que o dono já tinha ditado.
 */
@Component
public class PausarGatilhoFerramenta extends FerramentaDeGatilho {

    public PausarGatilhoFerramenta(Gatilhos gatilhos, JsonMapper json, JarvisProperties props) {
        super(gatilhos, json, props);
    }

    @Override public String nome() { return "pausar_gatilho"; }

    @Override public String descricao() {
        return "Pausa um gatilho (ele para de disparar, mas continua guardado) ou o religa com "
                + "reativar=true. Sem gatilho, vale o único ou o que acabou de ser criado.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode n = super.esquemaEntrada();
        ((ObjectNode) n.get("properties")).putObject("reativar").put("type", "boolean")
                .put("description", "true para religar um gatilho pausado.");
        return n;
    }

    @Override protected String responder(JsonNode entrada, long usuario) {
        var escolha = escolher(entrada, usuario);
        if (!escolha.unica()) return semEscolha(escolha, usuario);
        var regra = escolha.regra();
        boolean reativar = entrada != null && entrada.path("reativar").asBoolean(false);
        if (regra.ativa() == reativar)
            return (reativar ? "Esse gatilho já estava ligado: quando " : "Esse gatilho já estava pausado: quando ")
                    + regra.evento() + ".";
        try {
            gatilhos.mudarAtiva(regra.id(), usuario, reativar);
        } catch (IOException e) {
            return "Não consegui gravar a mudança; o gatilho ficou como estava.";
        }
        return reativar ? "Religado: quando " + regra.evento() + ", eu " + regra.instrucao() + "."
                : "Pausado: quando " + regra.evento() + ", não faço mais nada até você pedir para religar.";
    }

    @Override public String progresso() { return "mudando o gatilho"; }
}
