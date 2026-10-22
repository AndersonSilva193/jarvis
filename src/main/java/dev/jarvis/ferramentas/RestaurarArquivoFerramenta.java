package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Lista a lixeira e devolve um arquivo dela à pasta do Jarvis.
 *
 * <p><b>Sem {@code Aprovacoes}, de propósito.</b> Restaurar só traz de volta um arquivo que o
 * próprio usuário mandou apagar, e o agente nunca sobrescreve nada — se já existe um arquivo com
 * aquele nome, ele restaura como {@code nome-1.ext} e avisa qual nome usou. Pedir "Posso?" para
 * desfazer o que foi apagado com "Posso?" seria atrito sem contrapartida, o mesmo raciocínio do
 * {@code desfazer_edicao}.
 *
 * <p>Sem {@code idLixeira} a ferramenta <b>lista</b> em vez de adivinhar: o modelo apresenta as
 * opções e o usuário escolhe.
 */
@Component
public class RestaurarArquivoFerramenta implements Ferramenta {

    private final LixeiraPcService lixeira;
    private final JsonMapper json;

    public RestaurarArquivoFerramenta(LixeiraPcService lixeira, JsonMapper json) {
        this.lixeira = lixeira;
        this.json = json;
    }

    @Override
    public String nome() {
        return "restaurar_arquivo";
    }

    @Override
    public String descricao() {
        return """
                Traz de volta um arquivo que foi apagado, da lixeira do Jarvis. Não pede \
                confirmação: nada é sobrescrito.

                Sem idLixeira, devolve o que está na lixeira (nome, formato, data e prazo) para \
                você apresentar as opções — use isto quando o usuário disser apenas "restaura o \
                que eu apaguei". Com mais de um candidato parecido, pergunte qual antes de \
                restaurar; não escolha sozinho. Os filtros nome e formato e a paginação valem na \
                listagem.

                Com idLixeira, restaura aquele item. O arquivo volta com um ID de catálogo NOVO, \
                então releia o arquivo antes de propor qualquer edição nele.\
                """;
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode p = raiz.putObject("properties");
        p.putObject("idLixeira").put("type", "string")
                .put("description", "Item da listagem desta mesma ferramenta. Omita para listar.");
        p.putObject("nome").put("type", "string").put("maxLength", 120);
        var formato = p.putObject("formato").put("type", "string");
        var opcoes = formato.putArray("enum");
        for (String tipo : new String[] { "txt", "md", "json", "docx", "pdf", "xlsx", "csv" })
            opcoes.add(tipo);
        p.putObject("pagina").put("type", "integer").put("minimum", 1).put("maximum", 10000);
        p.putObject("tamanhoPagina").put("type", "integer").put("minimum", 1).put("maximum", 50);
        return raiz;
    }

    @Override
    public String executar(JsonNode entrada) throws Exception {
        return executarDetalhado(entrada, new OuvinteProgresso() { }).texto();
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto)
            throws Exception {
        if (!lixeira.online())
            return ResultadoFerramenta.texto("O PC está offline; a lixeira está indisponível.");
        String idLixeira = entrada == null ? "" : Json.texto(entrada.path("idLixeira")).trim();
        if (idLixeira.isEmpty()) return listar(entrada, contexto);
        if (!idLixeira.matches("[0-9a-fA-F-]{36}"))
            return ResultadoFerramenta.texto("Identificador da lixeira inválido; "
                    + "liste a lixeira e use o idLixeira de um item. Nada foi restaurado.");

        ResultadoPc resultado;
        try {
            resultado = lixeira.restaurar(idLixeira, contexto);
        } catch (IllegalStateException falha) {
            return ResultadoFerramenta.texto("Não restaurei: " + falha.getMessage());
        }
        if (!resultado.ok())
            return ResultadoFerramenta.texto("Não restaurei: " + resultado.mensagem());
        if (resultado.dados() == null)
            return ResultadoFerramenta.texto("O PC não disse qual arquivo voltou; "
                    + "confira com listar_arquivos.");

        JsonNode dados = resultado.dados();
        String nome = Json.texto(dados.path("nome"));
        String aviso = Json.texto(dados.path("aviso"));
        // O ID novo vai para o modelo porque uma edição na sequência precisa dele; a frase para o
        // usuário fala de nome, que é o que ele reconhece.
        return ResultadoFerramenta.texto("Restaurei " + nome + "."
                + (aviso.isBlank() ? "" : " " + aviso + ".")
                + " ID novo no catálogo: " + Json.texto(dados.path("id")) + ".");
    }

    private ResultadoFerramenta listar(JsonNode filtro, OuvinteProgresso contexto) throws Exception {
        ResultadoPc resultado;
        try {
            resultado = lixeira.listar(filtro, contexto);
        } catch (IllegalStateException falha) {
            return ResultadoFerramenta.texto("Não consultei a lixeira: " + falha.getMessage());
        }
        if (!resultado.ok())
            return ResultadoFerramenta.texto("Não consultei a lixeira: " + resultado.mensagem());
        if (resultado.dados() == null)
            return ResultadoFerramenta.texto("O PC não devolveu dados estruturados.");
        if (Json.numero(resultado.dados().path("total")) == 0)
            return ResultadoFerramenta.texto("A lixeira está vazia.");
        return ResultadoFerramenta.texto(resultado.dados().toString());
    }
}
