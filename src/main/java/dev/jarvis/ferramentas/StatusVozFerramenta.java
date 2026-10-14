package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;

import dev.jarvis.voz.VozService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Quanto de voz ainda sobra no mês e qual voz está no ar agora. */
@Component
public class StatusVozFerramenta implements Ferramenta {

    private final VozService voz;
    private final JsonMapper json;

    public StatusVozFerramenta(VozService voz, JsonMapper json) {
        this.voz = voz;
        this.json = json;
    }

    @Override
    public String nome() {
        return "status_voz";
    }

    @Override
    public String descricao() {
        return "Diz quanto crédito de voz ainda resta no mês e qual voz está sendo usada na HUD "
            + "(a da ElevenLabs ou a do navegador). Use em pedidos como 'quanto de voz ainda tenho', "
            + "'quanto sobrou da cota de voz', 'que voz você está usando' ou 'por que sua voz mudou'.";
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
        VozService.Status status = voz.status();
        if (status.limite() <= 0) {
            return "Voz atual: " + status.provedor() + ". Ainda não consultei a cota deste mês"
                    + (status.explicacao() == null ? "." : " (" + status.explicacao() + ").");
        }
        long restante = status.limite() - status.caracteresUsados();
        String texto = "Voz atual: " + status.provedor() + ". Usei " + status.caracteresUsados()
                + " de " + status.limite() + " caracteres (" + status.percentual() + "%), restam "
                + restante + ".";
        if (status.renovaEm() != null) {
            texto += " Renova em " + status.renovaEm().substring(0, 10) + ".";
        }
        if (status.explicacao() != null) {
            texto += " Estou na voz do navegador porque " + status.explicacao() + ".";
        }
        return texto;
    }
}
