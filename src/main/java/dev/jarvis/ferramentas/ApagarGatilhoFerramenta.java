package dev.jarvis.ferramentas;

import java.io.IOException;

import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.gatilhos.Gatilhos;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * "Apaga esse gatilho": sem aprovação, pela régua do {@code revogar_permissao} — tirar uma regra só
 * diminui o que o Jarvis faz sozinho. A regra migrada do {@code SITES_ACOES} apagada não volta.
 */
@Component
public class ApagarGatilhoFerramenta extends FerramentaDeGatilho {

    public ApagarGatilhoFerramenta(Gatilhos gatilhos, JsonMapper json, JarvisProperties props) {
        super(gatilhos, json, props);
    }

    @Override public String nome() { return "apagar_gatilho"; }

    @Override public String descricao() {
        return "Apaga um gatilho de vez ('apaga esse gatilho'). Sem gatilho, vale o único ou o que "
                + "acabou de ser criado; com vários, a ferramenta lista e você pergunta qual.";
    }

    @Override protected String responder(JsonNode entrada, long usuario) {
        var escolha = escolher(entrada, usuario);
        if (!escolha.unica()) return semEscolha(escolha, usuario);
        var regra = escolha.regra();
        try {
            if (gatilhos.apagar(regra.id(), usuario).isEmpty()) return "Não achei esse gatilho.";
        } catch (IOException e) {
            return "Não consegui apagar; o gatilho continua.";
        }
        return "Apagado: quando " + regra.evento() + ", não faço mais nada.";
    }

    @Override public String progresso() { return "apagando o gatilho"; }
}
