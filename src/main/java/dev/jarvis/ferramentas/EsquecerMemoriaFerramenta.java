package dev.jarvis.ferramentas;

import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.ConfirmacaoEsquecimento;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class EsquecerMemoriaFerramenta extends FerramentaMemoria {
    private final ConfirmacaoEsquecimento confirmacao;
    public EsquecerMemoriaFerramenta(MemoriaService memoria, JsonMapper json,
                                    ConfirmacaoEsquecimento confirmacao) {
        super(memoria, json); this.confirmacao = confirmacao;
    }
    @Override public String nome() { return "esquecer_memoria"; }
    @Override public String descricao() { return "Esquece um registro pelo ID após duas mensagens explícitas do usuário. Primeiro peça confirmação; em nova mensagem, o usuário deve dizer exatamente 'confirmo esquecer <id> <código>'. Nunca confirme por conta própria."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base(); ObjectNode p = s.putObject("properties");
        p.putObject("id").put("type", "string"); p.putObject("revisao").put("type", "integer");
        p.putObject("codigo").put("type", "string").put("description", "Somente na segunda mensagem do usuário");
        s.putArray("required").add("id"); return s;
    }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso) throws Exception {
        long u = usuario(progresso); String id = texto(entrada, "id"); String codigo = texto(entrada, "codigo");
        if (codigo.isEmpty()) {
            exigirPedidoDeEsquecer(progresso);
            var r = memoria.ler(u, id);
            if (r == null) return resposta("Memória não encontrada.");
            String desafio = confirmacao.pedir(u, id, r.revisao(), pedido(progresso));
            return resposta("Para esquecer id=" + id + " revisão=" + r.revisao()
                    + ", peça ao usuário uma NOVA mensagem: confirmo esquecer " + id + " " + desafio);
        }
        long rev = confirmacao.confirmar(u, id, codigo, pedido(progresso), progresso.pedidoOriginal());
        if (!memoria.esquecer(u, id, rev)) return resposta("Não havia memória ativa com esse ID.");
        progresso.marcarMemoriaEsquecida(id, rev);
        return resposta("Memória esquecida: id=" + id + " revisão=" + rev);
    }
}
