package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.ServidorService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Mostra a saúde do servidor onde o Jarvis roda: carga de CPU, memória, disco e tempo ligado. */
@Component
public class StatusServidorFerramenta implements Ferramenta {

    private final ServidorService servidor;
    private final JsonMapper json;

    public StatusServidorFerramenta(ServidorService servidor, JsonMapper json) {
        this.servidor = servidor;
        this.json = json;
    }

    @Override
    public String nome() {
        return "status_servidor";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.SISTEMA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Mostra o estado do servidor (VPS) onde o Jarvis está rodando: carga de CPU, memória, "
                + "espaço em disco e há quanto tempo está ligado.";
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
        return servidor.estado().detalhado();
    }
}
