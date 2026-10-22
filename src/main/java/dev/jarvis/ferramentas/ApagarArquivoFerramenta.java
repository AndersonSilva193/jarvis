package dev.jarvis.ferramentas;

import java.util.List;
import org.springframework.stereotype.Component;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Move um arquivo da pasta do Jarvis para a lixeira do agente, depois de o usuário autorizar.
 *
 * <p>É a única ferramenta de arquivo que passa pelo {@link Aprovacoes}, e a assimetria com a
 * edição é de propósito: editar deixa backup e tem {@code desfazer_edicao} no mesmo pedido, mas
 * apagar é o único caminho pelo qual um arquivo <b>sai de vista</b>. A lixeira o torna reversível,
 * não invisível — então vale um "Posso?", e o risco é {@link Aprovacoes.Risco#COMUM}, não
 * {@code CRITICO}: nada aqui é irreversível como desligar a máquina.
 *
 * <p>O nome que aparece na pergunta vem do <b>catálogo do agente</b>, nunca do modelo. É o que
 * garante que o "sim" do usuário vale para o arquivo que ele leu na frase.
 */
@Component
public class ApagarArquivoFerramenta implements Ferramenta {

    /**
     * O prazo que a frase promete. É o padrão do agente ({@code lixeira.diasRetencao}), que pode
     * ser mudado no {@code config.properties} do PC — a retenção é política de lá, e a VPS não tem
     * como consultá-la sem uma ida extra só para montar uma frase.
     */
    private static final int DIAS_PROMETIDOS = 30;

    private final LixeiraPcService lixeira;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;

    public ApagarArquivoFerramenta(LixeiraPcService lixeira, Aprovacoes aprovacoes,
            JsonMapper json) {
        this.lixeira = lixeira;
        this.aprovacoes = aprovacoes;
        this.json = json;
    }

    @Override
    public String nome() {
        return "apagar_arquivo";
    }

    @Override
    public String descricao() {
        return """
                Move um arquivo da pasta do Jarvis para a lixeira, pelo ID de listar_arquivos. \
                Dá para recuperar depois com restaurar_arquivo.

                Devolve uma pergunta de aprovação: repita a pergunta com o seu tom e espere a \
                resposta do usuário, sem pedir palavra, código nem frase exata. Chame uma vez só — \
                o servidor guarda a ação e consome a aprovação sem passar por você, então chamar de \
                novo não confirma nada. Não diga que apagou antes de o usuário responder.

                Se não tiver o ID, use listar_arquivos primeiro; com nomes parecidos, pergunte qual \
                antes de apagar, nunca escolha sozinho.\
                """;
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        raiz.putObject("properties").putObject("id").put("type", "string")
                .put("description", "ID do catálogo, como listar_arquivos devolve.");
        raiz.putArray("required").add("id");
        return raiz;
    }

    @Override
    public String executar(JsonNode entrada) {
        return "Pedido autenticado necessário.";
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso contexto)
            throws Exception {
        if (contexto.usuarioAutenticado() <= 0 || contexto.idPedido() == null)
            return ResultadoFerramenta.texto("Apagar arquivo requer pedido autenticado.");
        String id = entrada == null ? "" : Json.texto(entrada.path("id")).trim();
        if (!id.matches("[0-9a-fA-F-]{36}"))
            return ResultadoFerramenta.texto("ID inválido; use listar_arquivos para obtê-lo.");
        // Antes de tudo: travar o turno para no fim dizer "offline" é o pior dos mundos.
        if (!lixeira.online())
            return ResultadoFerramenta.texto("O PC está offline; não apaguei nada. "
                    + "Avise e ofereça tentar depois.");

        String nome;
        try {
            var achado = lixeira.nomePorId(id, contexto);
            if (achado.isEmpty())
                return ResultadoFerramenta.texto("Não achei esse arquivo no catálogo do PC. "
                        + "Use listar_arquivos e confira o ID; nada foi apagado.");
            nome = achado.get();
        } catch (IllegalStateException falha) {
            return ResultadoFerramenta.texto("Não consultei o catálogo: " + falha.getMessage()
                    + " Nada foi apagado.");
        }

        // A pergunta é montada aqui, de dado real, e é o que o usuário responde. Texto do modelo
        // neste lugar o deixaria escolher o que o "sim" significa.
        String pergunta = "Vou mover " + nome + " para a lixeira (dá para recuperar por "
                + DIAS_PROMETIDOS + " dias). Posso?";
        try {
            var confirmacao = aprovacoes.pedir("pc", "apagar " + nome, pergunta,
                    Aprovacoes.Risco.COMUM, contexto.usuarioAutenticado(), contexto.idPedido(),
                    () -> mover(id, nome, contexto));
            return new ResultadoFerramenta(pergunta, List.of(), List.of(), confirmacao,
                    List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException falhaConfirmacao) {
            return ResultadoFerramenta.texto(falhaConfirmacao.getMessage());
        }
    }

    /**
     * O que roda quando o {@code Assistente} consome a aprovação — fora do alcance do modelo.
     *
     * <p>O {@code id} e o {@code nome} estão capturados do momento do pedido: o modelo não refaz
     * nenhum dos dois na hora de executar.
     */
    private String mover(String id, String nome, OuvinteProgresso contexto) {
        if (!lixeira.online()) return "PC offline; " + nome + " não foi apagado.";
        ResultadoPc resultado;
        try {
            resultado = lixeira.mover(id, contexto);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Fui interrompido antes de confirmar; consulte status_operacao_pc.";
        } catch (IllegalStateException falha) {
            return "Não apaguei " + nome + ": " + falha.getMessage();
        } catch (Exception falha) {
            return "Não apaguei " + nome + "; a conversa com o PC falhou.";
        }
        if (!resultado.ok()) return "Não apaguei " + nome + ": " + resultado.mensagem();
        String aviso = resultado.dados() == null ? ""
                : Json.texto(resultado.dados().path("aviso"));
        return "Movi " + nome + " para a lixeira. Para trazer de volta, diga restaura."
                + (aviso.isBlank() ? "" : " (" + aviso + ")");
    }
}
