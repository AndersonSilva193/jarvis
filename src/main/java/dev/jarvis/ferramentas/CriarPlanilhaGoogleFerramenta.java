package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.google.GooglePlanilhasService;
import dev.jarvis.pc.PedidoArquivo;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Cria planilha nativa na conta autorizada; não altera documentos existentes. */
@Component
public class CriarPlanilhaGoogleFerramenta implements Ferramenta {
    private final GooglePlanilhasService google;
    private final JsonMapper json;
    public CriarPlanilhaGoogleFerramenta(GooglePlanilhasService google, JsonMapper json) {
        this.google = google; this.json = json;
    }
    @Override public String nome() { return "criar_planilha_google"; }
    @Override public String descricao() { return "Cria uma NOVA planilha nativa na conta Google autorizada. "
            + "Recebe nome, formato xlsx e abas estruturadas como criar_arquivo. "
            + "Sem autorização Google, explique que só é possível entregar XLSX para importação. "
            + "Nunca diga que criou planilha na conta se a ferramenta não retornou link."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode props = raiz.putObject("properties");
        props.putObject("nome").put("type", "string");
        props.putObject("formato").put("type", "string").put("const", "xlsx");
        EsquemaArquivo.planilha(props);
        EsquemaArquivo.fontes(props);
        props.putObject("pesquisaAtual").put("type", "boolean");
        raiz.putArray("required").add("nome").add("formato").add("abas");
        return raiz;
    }
    @Override public String executar(JsonNode entrada) throws Exception {
        String indisponivel = erroDeDisponibilidade();
        if (indisponivel != null) return indisponivel;
        String erro = PedidoArquivo.validar(entrada);
        if (!erro.isEmpty()) return erro;
        try { return "Planilha criada: " + google.criar(entrada); }
        catch (java.io.IOException e) {
            return "Não criei a planilha Google: " + e.getMessage()
                    + " Posso entregar XLSX para importação.";
        } catch (Exception e) {
            return "Erro ao criar a planilha Google; nenhum link foi confirmado. Posso entregar XLSX para importação.";
        }
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            dev.jarvis.conversa.OuvinteProgresso progresso) throws Exception {
        String indisponivel = erroDeDisponibilidade();
        if (indisponivel != null) return ResultadoFerramenta.texto(indisponivel);
        if (entrada.path("pesquisaAtual").asBoolean(false) && !progresso.pesquisaFeita())
            return ResultadoFerramenta.texto("Faça a busca web antes de criar planilha sobre fatos atuais.");
        String erro = PedidoArquivo.validar(entrada);
        if (!erro.isEmpty()) return ResultadoFerramenta.texto(erro);
        try { return ResultadoFerramenta.texto("Planilha criada: "
                + google.criar(entrada, progresso.idFerramenta())); }
        catch (java.io.IOException e) {
            return ResultadoFerramenta.texto("Não criei a planilha Google: " + e.getMessage()
                    + " Posso entregar XLSX para importação.");
        } catch (Exception e) {
            return ResultadoFerramenta.texto("Erro ao criar a planilha Google; nenhum link foi confirmado. "
                    + "Posso entregar XLSX para importação.");
        }
    }

    private String erroDeDisponibilidade() {
        if (!google.configurado())
            return "Erro: integração Google Sheets não configurada; planilha online não criada. "
                    + "Posso criar XLSX local para importação.";
        if (!google.autorizado())
            return "Erro: Google Sheets ainda não autorizado na HUD; planilha online não criada. "
                    + "Posso criar XLSX local para importação.";
        return null;
    }
}
