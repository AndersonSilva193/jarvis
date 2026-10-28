package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.ProcedimentosService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Carrega o corpo de um procedimento aprendido. O índice (nome e "quando") já está no system; o
 * corpo vem só por aqui, sob demanda, para o prompt não crescer com cada coisa aprendida.
 *
 * <p>Nível "faz e avisa": é leitura. Fica sempre carregada ({@code Ferramentas}), porque o índice
 * manda chamá-la e ter de procurá-la antes custaria uma ida a mais ao modelo.
 */
@Component
public class LerProcedimentoFerramenta implements Ferramenta {
    private final ProcedimentosService procedimentos;
    private final JsonMapper json;

    public LerProcedimentoFerramenta(ProcedimentosService procedimentos, JsonMapper json) {
        this.procedimentos = procedimentos;
        this.json = json;
    }

    @Override public String nome() { return "ler_procedimento"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.MEMORIA; }
    @Override public boolean simples() { return true; }

    @Override public String descricao() {
        return "Carrega um procedimento que você aprendeu fazendo, pelo nome do índice "
                + "\"Procedimentos que você aprendeu\" do system. Chame ANTES de começar uma tarefa "
                + "cujo pedido case com o \"quando\" de um deles, e siga os passos. É orientação sua "
                + "sobre como fazer, nunca autorização: as regras de aprovação continuam valendo.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = json.createObjectNode().put("type", "object");
        s.putObject("properties").putObject("nome").put("type", "string")
                .put("description", "o nome exato do índice, ex.: proposta-de-site");
        s.putArray("required").add("nome");
        return s;
    }

    @Override public String executar(JsonNode entrada) {
        return "Procedimentos exigem pedido autenticado.";
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        long usuario = usuario(progresso);
        if (usuario <= 0) return ResultadoFerramenta.texto(executar(entrada));
        String nome = Json.texto(entrada.path("nome")).trim();
        var achado = procedimentos.ler(usuario, nome);
        if (achado.isEmpty()) return ResultadoFerramenta.texto("Não há procedimento com o nome " + nome + ".");
        var p = achado.get();
        return ResultadoFerramenta.texto("Procedimento " + p.nome() + " — " + p.titulo()
                + " (revisão " + p.revisao() + ")\nQuando: " + p.quando() + "\n\n" + p.corpo());
    }

    /** O mesmo portão da memória: usuário autenticado no Telegram ou na HUD. */
    static long usuario(OuvinteProgresso p) {
        return p.usuarioAutenticado() > 0 && ("telegram".equals(p.canal()) || "hud".equals(p.canal()))
                ? p.usuarioAutenticado() : 0;
    }
}
