package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.EmpresaPcService;
import dev.jarvis.pc.OperacoesPcService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Abre o escritório 3D da empresa no navegador do PC. */
@Component
public class EmpresaAbrirFerramenta implements Ferramenta {
    private final EmpresaPcService empresa;
    private final OperacoesPcService e2;
    private final JsonMapper json;

    public EmpresaAbrirFerramenta(EmpresaPcService empresa, OperacoesPcService e2, JsonMapper json) {
        this.empresa = empresa;
        this.e2 = e2;
        this.json = json;
    }

    @Override public String nome() { return "empresa_abrir"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.PC; }
    @Override public boolean simples() { return true; }
    @Override public boolean mudaEstado() { return true; }

    @Override public String descricao() {
        return "Abre no navegador do PC o escritório 3D da empresa do dono, no Hermes3D. "
                + "Use quando ele pedir para abrir a empresa ou o escritório.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode().put("type", "object");
        esquema.putObject("properties");
        return esquema;
    }

    @Override public String executar(JsonNode entrada) {
        return "Pedido autenticado necessario.";
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto) {
        if (!e2.online()) return ResultadoFerramenta.pcOffline(EmpresaPcService.PC_OFFLINE);
        return ResultadoFerramenta.texto(empresa.abrir(contexto.canal(), contexto.usuarioAutenticado()));
    }
}
