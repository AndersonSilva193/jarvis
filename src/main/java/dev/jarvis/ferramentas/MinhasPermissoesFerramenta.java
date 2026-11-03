package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.autonomia.PoliticaDeAutonomia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * "O que você pode fazer sem me perguntar?": a política de autonomia em português corrido.
 *
 * <p>O texto vem inteiro da {@link PoliticaDeAutonomia}, que é quem decide de verdade; a ferramenta
 * não tem lista própria, senão poderia contar ao dono uma regra que o código não segue. A descrição
 * é constante de propósito: a política muda quando o {@code autonomia.md} muda, e a definição da
 * ferramenta está no prefixo do cache de prompt.
 */
@Component
public class MinhasPermissoesFerramenta implements Ferramenta {

    private final PoliticaDeAutonomia politica;
    private final JsonMapper json;

    public MinhasPermissoesFerramenta(PoliticaDeAutonomia politica, JsonMapper json) {
        this.politica = politica;
        this.json = json;
    }

    @Override
    public String nome() {
        return "minhas_permissoes";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Diz o que você faz sozinho, o que pergunta antes e o que nunca faz, conforme a "
                + "configuração de autonomia em vigor. Use para 'o que você pode fazer sem me "
                + "perguntar?' e parecidas; responda com as próprias palavras, curto.";
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
        return politica.explicar();
    }

    @Override
    public String progresso() {
        return "consultando as permissões";
    }
}
