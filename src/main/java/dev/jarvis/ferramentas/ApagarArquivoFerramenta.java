package dev.jarvis.ferramentas;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.LixeiraPcService;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Move arquivos da pasta do Jarvis para a lixeira do agente, sem perguntar.
 *
 * <p><b>É "faz e avisa".</b> O arquivo não sai de vista: ele fica em
 * {@code <pastaArquivos>/.lixeira/AAAA-MM-DD/} com os bytes e o registro ao lado, e volta pelo
 * {@code restaurar_arquivo}, que também não pergunta. Cobrar um "Posso?" por arquivo — e outro para
 * voltar atrás — era o mesmo atrito sem contrapartida que o {@code desfazer_edicao} já tinha
 * evitado.
 *
 * <p><b>A assimetria mudou de lugar</b>: não é mais entre apagar e restaurar, é entre apagar um e
 * apagar em massa. Até {@link #MAX_SEM_PERGUNTA} arquivos no mesmo pedido saem na hora; a chamada
 * que estoura esse orçamento <b>não move nada</b> e vira <b>uma</b> pendência no {@link Aprovacoes}
 * para a lista inteira. Mover metade e perguntar o resto deixaria um estado meio-feito que ninguém
 * pediu, e uma pergunta por arquivo treinaria o dono a dizer "sim" sem ler.
 *
 * <p>Os nomes que aparecem na pergunta e na resposta vêm do <b>catálogo do agente</b>, nunca do
 * modelo. É o que garante que o "sim" do usuário vale para os arquivos que ele leu na frase.
 */
@Component
public class ApagarArquivoFerramenta implements Ferramenta {

    /** O teto de IDs por chamada. Acima disso o pedido é para ser dividido, não aceito. */
    static final int MAX_IDS = 50;

    /** Quantos arquivos o mesmo pedido apaga sem perguntar. O 6º leva a chamada à aprovação. */
    static final int MAX_SEM_PERGUNTA = 5;

    /**
     * O que nunca é apagável, conferido também aqui.
     *
     * <p>O catálogo do agente já não lista a {@code .lixeira} nem os backups de edição, e o agente
     * recusa de novo no momento do movimento. Esta é a terceira trava, pela mesma regra do
     * {@code abrir_url}: o lado que tem poder na máquina não confia na checagem do outro.
     */
    private static final Set<String> NOMES_PROTEGIDOS =
            Set.of(".lixeira", "lixeira", "backup", "backups");

    private final LixeiraPcService lixeira;
    private final Aprovacoes aprovacoes;
    private final JsonMapper json;
    /** Pode ser nulo: os testes antigos montam a ferramenta sem o diário. */
    private final DiarioDeAcoes diario;

    /**
     * O orçamento do pedido em andamento.
     *
     * <p>Um par de campos, e não um mapa por {@code idPedido}: o {@code Assistente.responder}
     * segura um {@code ReentrantLock} justo pelo turno inteiro, então só um pedido corre por vez e
     * um mapa precisaria de evicção para não vazar em troca de nada.
     */
    private String ultimoPedido;
    private int apagadosNoPedido;

    public ApagarArquivoFerramenta(LixeiraPcService lixeira, Aprovacoes aprovacoes,
            JsonMapper json) {
        this(lixeira, aprovacoes, json, null);
    }

    @Autowired
    public ApagarArquivoFerramenta(LixeiraPcService lixeira, Aprovacoes aprovacoes, JsonMapper json,
            DiarioDeAcoes diario) {
        this.lixeira = lixeira;
        this.aprovacoes = aprovacoes;
        this.json = json;
        this.diario = diario;
    }

    @Override
    public String nome() {
        return "apagar_arquivo";
    }

    @Override
    public String descricao() {
        return """
                Move arquivos da pasta do Jarvis para a lixeira, pelos IDs de listar_arquivos. \
                Apaga direto, sem perguntar: dá para trazer de volta com restaurar_arquivo.

                Um ID por arquivo em ids. Acima de 5 arquivos no mesmo pedido, o servidor devolve \
                UMA pergunta de aprovação para a lista inteira e não move nada até a resposta: \
                nesse caso repita a pergunta com o seu tom, sem pedir palavra, código nem frase \
                exata, e não diga que apagou antes de o usuário responder.

                Se não tiver o ID, use listar_arquivos primeiro; com arquivos parecidos, pergunte \
                qual antes de apagar, nunca escolha sozinho.\
                """;
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode ids = raiz.putObject("properties").putObject("ids").put("type", "array")
                .put("minItems", 1).put("maxItems", MAX_IDS)
                .put("description", "IDs do catálogo, como listar_arquivos devolve.");
        ids.putObject("items").put("type", "string");
        raiz.putArray("required").add("ids");
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
        List<String> ids = idsDe(entrada);
        if (ids.isEmpty())
            return ResultadoFerramenta.texto("Nenhum ID; use listar_arquivos para obtê-los.");
        if (ids.size() > MAX_IDS)
            return ResultadoFerramenta.texto("São " + ids.size() + " arquivos de uma vez, e o teto "
                    + "é " + MAX_IDS + ". Divida o pedido; nada foi apagado.");
        for (String id : ids)
            if (!id.matches("[0-9a-fA-F-]{36}"))
                return ResultadoFerramenta.texto("ID inválido; use listar_arquivos para obtê-lo.");
        // Antes de tudo: travar o turno para no fim dizer "offline" é o pior dos mundos.
        if (!lixeira.online())
            return ResultadoFerramenta.texto("O PC está offline; não apaguei nada. "
                    + "Avise e ofereça tentar depois.");

        Map<String, String> nomes;
        try {
            nomes = lixeira.nomesPorIds(ids, contexto);
        } catch (IllegalStateException falha) {
            return ResultadoFerramenta.texto("Não consultei o catálogo: " + falha.getMessage()
                    + " Nada foi apagado.");
        }
        long faltando = ids.stream().filter(id -> !nomes.containsKey(id.toLowerCase(Locale.ROOT)))
                .count();
        if (faltando > 0)
            return ResultadoFerramenta.texto(ids.size() == 1
                    ? "Não achei esse arquivo no catálogo do PC. "
                            + "Use listar_arquivos e confira o ID; nada foi apagado."
                    : "Não achei " + faltando + " dos " + ids.size() + " arquivos no catálogo do "
                            + "PC. Use listar_arquivos e confira os IDs; nada foi apagado.");

        List<Alvo> alvos = new ArrayList<>();
        for (String id : ids) alvos.add(new Alvo(id, nomes.get(id.toLowerCase(Locale.ROOT))));
        for (Alvo alvo : alvos)
            if (!apagavel(alvo.nome()))
                return ResultadoFerramenta.texto("Não apago " + alvo.nome() + ": a lixeira e os "
                        + "backups de edição não saem de vista. Nada foi apagado.");

        String pedido = contexto.idPedido();
        if (cabeNoOrcamento(pedido, alvos.size()))
            return ResultadoFerramenta.texto(mover(alvos, pedido, contexto));

        // A pergunta é montada aqui, dos nomes do catálogo, e é o que o usuário responde. Texto do
        // modelo neste lugar o deixaria escolher o que o "sim" significa.
        String pergunta = "Vou mover " + alvos.size() + " arquivos para a lixeira: "
                + emFrase(alvos.stream().map(Alvo::nome).toList()) + ". Posso?";
        try {
            var confirmacao = aprovacoes.pedir(Aprovacoes.TIPO_PC_LIXEIRA,
                    "apagar " + alvos.size() + " arquivos", pergunta, Aprovacoes.Risco.COMUM,
                    contexto.usuarioAutenticado(), pedido, () -> mover(alvos, pedido, contexto));
            return new ResultadoFerramenta(pergunta, List.of(), List.of(), confirmacao,
                    List.of(), List.of());
        } catch (IllegalArgumentException | IllegalStateException falhaConfirmacao) {
            return ResultadoFerramenta.texto(falhaConfirmacao.getMessage());
        }
    }

    /** Um arquivo do pedido: o ID que o modelo mandou e o nome que o catálogo do agente devolveu. */
    private record Alvo(String id, String nome) { }

    /**
     * Move a lista e devolve a frase que afirma o que foi feito.
     *
     * <p>É o <b>mesmo</b> método nos dois caminhos — a chamada direta e o {@code Supplier} da
     * pendência —, então mover, anotar no diário e contar no orçamento acontecem num lugar só.
     *
     * <p>Falha de um arquivo não derruba os outros: o pedido era apagar a lista, e parar no
     * primeiro arquivo aberto no Excel deixaria o resto por fazer sem ninguém saber. A frase conta
     * as duas metades.
     */
    private String mover(List<Alvo> alvos, String pedido, OuvinteProgresso contexto) {
        List<String> apagados = new ArrayList<>();
        List<String> falhas = new ArrayList<>();
        int dias = 0;
        for (Alvo alvo : alvos) {
            if (!lixeira.online()) {
                falhas.add(alvo.nome() + ": o PC está offline");
                continue;
            }
            ResultadoPc resultado;
            try {
                resultado = lixeira.mover(alvo.id(), contexto);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                falhas.add(alvo.nome() + ": fui interrompido antes de confirmar; "
                        + "consulte status_operacao_pc");
                break;
            } catch (IllegalStateException falha) {
                falhas.add(alvo.nome() + ": " + falha.getMessage());
                continue;
            } catch (Exception falha) {
                falhas.add(alvo.nome() + ": a conversa com o PC falhou");
                continue;
            }
            if (!resultado.ok()) {
                falhas.add(alvo.nome() + ": " + resultado.mensagem());
                continue;
            }
            apagados.add(alvo.nome());
            contarApagado(pedido);
            anotar(alvo.nome(), resultado, contexto);
            if (dias == 0) dias = diasDeRetencao(resultado);
        }
        return frase(apagados, falhas, dias);
    }

    /**
     * A linha do diário, uma por arquivo apagado.
     *
     * <p>Apagar virou "faz e avisa", então o diário é o único jeito de o dono ver depois que um
     * arquivo saiu — com o nome que o agente devolveu e o {@code idLixeira} que o traz de volta.
     */
    private void anotar(String nome, ResultadoPc resultado, OuvinteProgresso contexto) {
        if (diario == null) return;
        String idLixeira = resultado.dados() == null ? ""
                : Json.texto(resultado.dados().path("idLixeira"));
        diario.registrarDoCanal(TipoDeAcao.APAGAR_ARQUIVO, "apaguei " + nome, contexto.canal(),
                idLixeira);
    }

    /**
     * Quantos dias o arquivo fica na lixeira, segundo o próprio agente.
     *
     * <p>A retenção é política do PC ({@code lixeira.diasRetencao}), e o {@code mover_para_lixeira}
     * já devolve a {@code data} e o {@code expiraEm} — a diferença entre as duas é o prazo real,
     * sem ida extra e sem a VPS repetir um número que não é dela. Zero quando o agente não disse,
     * e aí a frase sai sem número: é melhor não prometer prazo do que prometer o errado.
     */
    private static int diasDeRetencao(ResultadoPc resultado) {
        if (resultado.dados() == null) return 0;
        try {
            long dias = ChronoUnit.DAYS.between(
                    LocalDate.parse(Json.texto(resultado.dados().path("data"))),
                    LocalDate.parse(Json.texto(resultado.dados().path("expiraEm"))));
            return dias > 0 && dias <= 3650 ? (int) dias : 0;
        } catch (RuntimeException naoEhData) {
            return 0;
        }
    }

    private static String frase(List<String> apagados, List<String> falhas, int dias) {
        if (apagados.isEmpty())
            return falhas.size() == 1 ? "Não apaguei " + falhas.get(0) + "."
                    : "Não apaguei nenhum: " + String.join("; ", falhas) + ".";
        StringBuilder frase = new StringBuilder("Apaguei ");
        if (apagados.size() == 1) frase.append(apagados.get(0));
        else frase.append(apagados.size()).append(" arquivos: ").append(emFrase(apagados));
        frase.append(apagados.size() == 1 ? ". Fica na lixeira" : ". Ficam na lixeira");
        if (dias > 0) frase.append(" por ").append(dias).append(dias == 1 ? " dia" : " dias");
        frase.append("; se quiser, eu volto.");
        if (!falhas.isEmpty())
            frase.append(" Não apaguei ").append(String.join("; ", falhas)).append('.');
        return frase.toString();
    }

    /**
     * Os nomes numa frase falável: todos até três, e acima disso os dois primeiros mais a conta.
     *
     * <p>Doze nomes de arquivo lidos em voz alta não são uma pergunta, são uma lista — e a decisão
     * que o dono toma é sobre a quantidade, que a frase diz antes.
     */
    private static String emFrase(List<String> nomes) {
        if (nomes.size() == 1) return nomes.get(0);
        if (nomes.size() == 2) return nomes.get(0) + " e " + nomes.get(1);
        if (nomes.size() == 3)
            return nomes.get(0) + ", " + nomes.get(1) + " e " + nomes.get(2);
        return nomes.get(0) + ", " + nomes.get(1) + " e mais " + (nomes.size() - 2);
    }

    /**
     * Confere o orçamento do pedido, zerando-o quando o pedido é outro.
     *
     * <p>A chamada que estoura o orçamento é recusada <b>inteira</b>, não aparada até caber: cinco
     * arquivos apagados e sete esperando "Posso?" é um estado que ninguém pediu.
     */
    private synchronized boolean cabeNoOrcamento(String pedido, int quantos) {
        if (!pedido.equals(ultimoPedido)) {
            ultimoPedido = pedido;
            apagadosNoPedido = 0;
        }
        return apagadosNoPedido + quantos <= MAX_SEM_PERGUNTA;
    }

    /**
     * Soma um arquivo ao orçamento, se o pedido ainda é o mesmo.
     *
     * <p>O {@code Supplier} da pendência roda no turno <b>seguinte</b>, carregando o id do pedido
     * que a criou: sem a conferência, o lote aprovado somaria no orçamento de um pedido que já
     * acabou.
     */
    private synchronized void contarApagado(String pedido) {
        if (pedido.equals(ultimoPedido)) apagadosNoPedido++;
    }

    /** Os IDs pedidos, sem repetição e na ordem em que vieram. */
    private static List<String> idsDe(JsonNode entrada) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (entrada == null) return List.of();
        for (JsonNode no : entrada.path("ids")) {
            String id = Json.texto(no).trim();
            if (!id.isEmpty()) ids.add(id);
        }
        // O schema anuncia só "ids", mas o modelo erra o formato no meio do turno e recusar um
        // "id" solto seria gastar um passo do laço para não dizer nada de novo.
        if (ids.isEmpty()) {
            String id = Json.texto(entrada.path("id")).trim();
            if (!id.isEmpty()) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** O nome catalogado é de arquivo na pasta do Jarvis, e nunca da lixeira ou dos backups. */
    private static boolean apagavel(String nome) {
        if (nome == null || nome.isBlank()) return false;
        if (nome.contains("/") || nome.contains("\\") || nome.contains("..")) return false;
        if (nome.startsWith(".")) return false;
        String base = nome.toLowerCase(Locale.ROOT);
        int ponto = base.lastIndexOf('.');
        if (ponto > 0) base = base.substring(0, ponto);
        return !NOMES_PROTEGIDOS.contains(base);
    }
}
