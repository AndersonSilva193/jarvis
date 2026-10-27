package dev.jarvis.ferramentas;

import java.util.List;

import org.springframework.stereotype.Component;

import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.memoria.EsquecimentosDeMemoria;
import dev.jarvis.memoria.MemoriaService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Apaga um registro da memória, depois de o usuário autorizar.
 *
 * <p>É a única ferramenta de memória que passa pelo {@link Aprovacoes}, e a assimetria com corrigir
 * e desfazer é de propósito: corrigir deixa a versão anterior no {@code .historico} e
 * {@code desfazer_anotacao} só tira o que o Jarvis acabou de gravar, mas esquecer é o único caminho
 * pelo qual um fato <b>sai de vista</b> — e leva o histórico da conversa junto. Risco
 * {@link Aprovacoes.Risco#COMUM}: é definitivo, mas não é desligar a máquina.
 *
 * <p>A pergunta é montada em Java, do <b>registro que está no disco</b>, nunca do que o modelo
 * escreveu. É o que garante que o "sim" do usuário vale para a lembrança que ele leu na frase.
 *
 * <p>Quem apaga de verdade é o {@code Supplier}, com id e revisão capturados na hora do pedido, e o
 * {@code Assistente} o roda fora do alcance do modelo. O recado de que o apagamento aconteceu vai
 * pelo {@link EsquecimentosDeMemoria}: o {@code Supplier} roda num turno cujo {@code progresso} ele
 * não conhece, e é o {@code progresso} daquele turno que decide apagar o histórico da conversa.
 */
@Component
public class EsquecerMemoriaFerramenta extends FerramentaMemoria {

    /** Teto do conteúdo citado na pergunta: ela é para o usuário reconhecer, não para reler tudo. */
    private static final int CURTO = 80;

    private final Aprovacoes aprovacoes;
    private final EsquecimentosDeMemoria esquecimentos;

    public EsquecerMemoriaFerramenta(MemoriaService memoria, JsonMapper json, Aprovacoes aprovacoes,
            EsquecimentosDeMemoria esquecimentos) {
        super(memoria, json);
        this.aprovacoes = aprovacoes;
        this.esquecimentos = esquecimentos;
    }

    @Override public String nome() { return "esquecer_memoria"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.MEMORIA; }
    @Override public boolean simples() { return false; }

    @Override public String descricao() {
        return "Esquece ou apaga para sempre um registro da memória pelo ID de "
                + "buscar_memoria. Registros parecidos exigem escolha do usuário. A ferramenta "
                + "só faz a pergunta de aprovação: chame uma vez e espere a resposta em "
                + "linguagem natural, sem pedir palavra, código nem frase exata. Não diga que apagou antes. "
                + "O servidor executa a aprovação sem outra chamada da ferramenta. Para desfazer "
                + "uma anotação sua feita agora há pouco, use desfazer_anotacao.";
    }

    @Override public ObjectNode esquemaEntrada() {
        ObjectNode s = base();
        s.putObject("properties").putObject("id").put("type", "string")
                .put("description", "ID do registro, como buscar_memoria devolve.");
        s.putArray("required").add("id");
        return s;
    }

    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        long u = usuario(progresso);
        String id = texto(entrada, "id");
        var r = memoria.ler(u, id);
        // Pendência sobre registro que não existe faria o usuário responder "Posso?" sobre nada.
        if (r == null) return resposta("Não achei esse registro na memória; nada foi apagado.");

        long revisao = r.revisao();
        String assunto = r.assunto();
        String pergunta = "Vou apagar da memória: '" + assunto + ": " + curto(r.conteudo())
                + "'. Posso?";
        try {
            var confirmacao = aprovacoes.pedir("memoria", "esquecer " + assunto, pergunta,
                    Aprovacoes.Risco.COMUM, u, pedido(progresso),
                    () -> apagar(u, id, revisao, assunto));
            return new ResultadoFerramenta(pergunta, List.of(), List.of(), confirmacao,
                    List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException recusa) {
            return resposta(recusa.getMessage());
        }
    }

    /**
     * O que roda quando o {@code Assistente} consome a aprovação — fora do alcance do modelo, com
     * id, revisão e assunto capturados do momento do pedido.
     */
    private String apagar(long usuario, String id, long revisao, String assunto) {
        try {
            if (!memoria.esquecer(usuario, id, revisao))
                return "Aquele registro já não estava na memória.";
        } catch (MemoriaService.Conflito mudou) {
            return "Não apaguei " + assunto + ": " + mudou.getMessage();
        } catch (Exception falha) {
            return "Não consegui apagar " + assunto + " da memória.";
        }
        esquecimentos.marcar(usuario, id, revisao);
        return "Esqueci: " + assunto + ".";
    }

    private static String curto(String conteudo) {
        String plano = conteudo.replaceAll("\\s+", " ").trim();
        return plano.length() <= CURTO ? plano : plano.substring(0, CURTO) + "…";
    }
}
