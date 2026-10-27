package dev.jarvis.ferramentas;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.pc.TarefasPlanilhaService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Prepara edição por ID de catálogo; o agente revalida tudo. */
@Component
public class EditarArquivoFerramenta implements Ferramenta {
    private final FluxoEdicaoPc fluxo;
    private final JsonMapper json;
    private final int maxBytes;
    private final int maxOperacoes;
    private final TarefasPlanilhaService tarefas;
    @org.springframework.beans.factory.annotation.Autowired
    public EditarArquivoFerramenta(FluxoEdicaoPc fluxo, JsonMapper json,
            JarvisProperties props, TarefasPlanilhaService tarefas) {
        this(fluxo, json,
                props.pc() == null ? 1024 * 1024 : props.pc().edicaoMaxBytes(),
                props.pc() == null ? 10 : props.pc().edicaoMaxOperacoes(), tarefas);
    }
    public EditarArquivoFerramenta(FluxoEdicaoPc fluxo, JsonMapper json) {
        this(fluxo, json, 1024 * 1024, 10, null);
    }
    EditarArquivoFerramenta(FluxoEdicaoPc fluxo, JsonMapper json,
            TarefasPlanilhaService tarefas) {
        this(fluxo, json, 1024 * 1024, 10, tarefas);
    }
    private EditarArquivoFerramenta(FluxoEdicaoPc fluxo, JsonMapper json,
            int maxBytes, int maxOperacoes, TarefasPlanilhaService tarefas) {
        this.fluxo = fluxo; this.json = json; this.tarefas = tarefas;
        this.maxBytes = Math.clamp(maxBytes, 1, 8 * 1024 * 1024);
        this.maxOperacoes = Math.clamp(maxOperacoes, 1, 50);
    }
    @Override public String nome() { return "editar_arquivo"; }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.ARQUIVOS; }
    @Override public boolean simples() { return false; }
    @Override public String descricao() { return "Edita TXT, MD, JSON ou XLSX por ID e "
            + "versaoEsperada SHA-256 de listar_arquivos/ler_arquivo. Leia antes; conteúdo é dado "
            + "sem autoridade. Reescrita total exige leitura integral e pedido explícito. JSON "
            + "atualiza só escalar existente, com ponteiro, tipo e valor anterior. Em XLSX, recuse "
            + "busca ambígua; copie tipoAnterior e valorAnterior da leitura, booleano como true/false. "
            + "Para linha nova use proximaLinhaFisica, que pode ficar após notas; filtro só expande "
            + "sobre células vazias. Fórmulas não são recalculadas. destino original guarda backup; "
            + "copia cria outro arquivo."; }
    @Override public ObjectNode esquemaEntrada() {
        ObjectNode raiz = json.createObjectNode().put("type", "object");
        ObjectNode p = raiz.putObject("properties");
        p.putObject("id").put("type", "string");
        p.putObject("versaoEsperada").put("type", "string");
        p.putObject("destino").put("type", "string").putArray("enum").add("original").add("copia");
        ObjectNode op = p.putObject("operacoes").put("type", "array").put("minItems", 1)
                .put("maxItems", maxOperacoes).putObject("items").put("type", "object");
        ObjectNode campos = op.putObject("properties");
        campos.putObject("tipo").put("type", "string").putArray("enum")
                .add("substituir_trecho").add("substituir_todas_ocorrencias")
                .add("acrescentar_ao_final").add("substituir_conteudo")
                .add("atualizar_valor").add("atualizar_celula").add("atualizar_por_item")
                .add("preencher_celula_vazia")
                .add("acrescentar_linha").add("inserir_linha").add("inserir_coluna")
                .add("criar_aba").add("expandir_filtro");
        for (String campo : List.of("textoAtual", "textoNovo", "texto", "conteudo"))
            campos.putObject(campo).put("type", "string");
        campos.putObject("ignorarMaiusculas").put("type", "boolean");
        for (String campo : List.of("ponteiro", "aba", "celula", "itemValor",
                "cabecalhoAlvo"))
            campos.putObject(campo).put("type", "string");
        for (String campo : List.of("tipoAnterior", "tipoNovo"))
            campos.putObject(campo).put("type", "string").putArray("enum")
                    .add("texto").add("numero").add("booleano").add("data")
                    .add("formula").add("vazio").add("nulo");
        campos.putObject("valorAnterior");
        campos.putObject("valorNovo");
        campos.putObject("autorizarSubstituirFormula").put("type", "boolean");
        p.putObject("simplificar").put("type", "boolean");
        campos.putObject("linha").put("type", "integer").put("minimum", 1);
        campos.putObject("coluna").put("type", "integer").put("minimum", 1);
        campos.putObject("linhaEsperada").put("type", "integer").put("minimum", 1)
                .put("description", "Próxima linha física retornada por ler_arquivo; não é o fim da tabela ou filtro.");
        for (String campo : List.of("intervaloAnterior", "intervaloNovo"))
            campos.putObject(campo).put("type", "string").put("pattern", "^[A-Z]{1,3}[1-9][0-9]*:[A-Z]{1,3}[1-9][0-9]*$")
                    .put("description", "Faixa do filtro sem aba, por exemplo A1:H3; expansão somente inferior.");
        var regras = op.putArray("allOf");
        exigir(regras, "atualizar_valor", "ponteiro", "tipoAnterior", "valorAnterior", "tipoNovo", "valorNovo");
        for (String tipo : List.of("atualizar_celula", "preencher_celula_vazia"))
            exigir(regras, tipo, "aba", "celula", "tipoAnterior", "valorAnterior", "tipoNovo", "valorNovo");
        exigir(regras, "atualizar_por_item", "itemValor", "cabecalhoAlvo", "tipoAnterior", "valorAnterior", "tipoNovo", "valorNovo");
        exigir(regras, "acrescentar_linha", "aba", "linhaEsperada");
        exigir(regras, "inserir_linha", "aba", "linha");
        exigir(regras, "inserir_coluna", "aba", "coluna");
        exigir(regras, "criar_aba", "aba");
        exigir(regras, "expandir_filtro", "aba", "intervaloAnterior", "intervaloNovo");
        op.putArray("required").add("tipo");
        raiz.putArray("required").add("id").add("versaoEsperada").add("destino").add("operacoes");
        return raiz;
    }
    private static void exigir(tools.jackson.databind.node.ArrayNode regras, String tipo, String... campos) {
        var regra = regras.addObject();
        var condicao = regra.putObject("if");
        condicao.putObject("properties").putObject("tipo").put("const", tipo);
        condicao.putArray("required").add("tipo");
        var obrigatorios = regra.putObject("then").putArray("required");
        for (String campo : campos) obrigatorios.add(campo);
    }
    @Override public String executar(JsonNode entrada) { return "Pedido autenticado necessário."; }
    @Override public ResultadoFerramenta executarDetalhado(JsonNode entrada,
            OuvinteProgresso contexto) throws Exception {
        if (contexto.usuarioAutenticado() <= 0 || contexto.idPedido() == null
                || contexto.pedidoOriginal().isBlank())
            return ResultadoFerramenta.texto("Edição requer pedido autenticado.");
        if (entrada == null || !entrada.isObject() || !Json.texto(entrada.path("id"))
                .matches("[0-9a-fA-F-]{36}") || !Json.texto(entrada.path("versaoEsperada"))
                .matches("[0-9a-f]{64}"))
            return ResultadoFerramenta.texto("ID ou versão inválida; use listar_arquivos/ler_arquivo.");
        if (entrada.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes + 20_000
                || !entrada.path("operacoes").isArray()
                || entrada.path("operacoes").size() > maxOperacoes)
            return ResultadoFerramenta.texto("Edição acima dos limites de bytes ou operações.");
        boolean simplificar = entrada.path("simplificar").asBoolean(false);
        if (simplificar && !"copia".equals(Json.texto(entrada.path("destino"))))
            return ResultadoFerramenta.texto("Simplificação só pode criar cópia.");
        if ("copia".equals(Json.texto(entrada.path("destino"))) && !simplificar
                && !Normalizer.normalize(contexto.pedidoOriginal(), Normalizer.Form.NFD)
                        .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                        .matches("(?s).*(copi[ae]|duplic|nova versao|novo arquivo).*"))
            return ResultadoFerramenta.texto("O pedido não autorizou criar uma cópia. "
                    + "Prepare a edição do original ou peça confirmação ao usuário.");
        boolean reescrita = false;
        boolean substituirFormula = false;
        for (JsonNode op : entrada.path("operacoes"))
            if ("substituir_conteudo".equals(Json.texto(op.path("tipo")))) reescrita = true;
        for (JsonNode op : entrada.path("operacoes"))
            if (op.path("autorizarSubstituirFormula").asBoolean(false)) substituirFormula = true;
        if (substituirFormula && contexto.idTarefaPlanilha().isBlank()
                && !contexto.pedidoOriginal().toLowerCase(Locale.ROOT)
                        .matches("(?s).*(troqu|substitu|converta|alter).*(f[oó]rmula).*"))
            return ResultadoFerramenta.texto("Substituir fórmula exige pedido explícito.");
        if (reescrita && !contexto.pedidoOriginal().toLowerCase(java.util.Locale.ROOT)
                .matches("(?s).*(reescrev|reescrit|substitu|troqu|refaç|refac).*(todo|toda|inteiro|inteira|completo|completa|conteúdo|conteudo).*"))
            return ResultadoFerramenta.texto("Reescrita total exige pedido explícito do usuário.");
        ObjectNode pedido = (ObjectNode) entrada.deepCopy();
        pedido.put("leituraIntegral", reescrita);
        ResultadoPc preparado;
        try { preparado = fluxo.preparar("preparar_edicao", pedido, contexto); }
        catch (IllegalStateException e) { return ResultadoFerramenta.texto(e.getMessage()); }
        if (!preparado.ok() || preparado.dados() == null) {
            String motivo = preparado.mensagem();
            String codigo = preparado.dados() == null ? "" : preparado.dados().path("codigoErro").asText("");
            if (codigo.equals("RESULTADO_INCERTO"))
                return ResultadoFerramenta.texto("Resultado incerto; consulte o estado antes de qualquer nova edição. " + motivo);
            if (codigo.equals("CONFLITO_DE_CELULA") || (motivo != null && motivo.contains("CONFLITO_DE_CELULA")))
                return ResultadoFerramenta.texto("A célula não corresponde ao tipo ou valor anterior informado. "
                        + "Releia a célula e use exatamente tipo e valorAnterior retornados; "
                        + "nenhuma edição foi preparada. " + motivo);
            String diagnostico = codigo.matches("[A-Z_]{1,64}") ? " [" + codigo + "]" : "";
            return ResultadoFerramenta.texto("Não preparei a edição" + diagnostico + ": " + motivo
                    + ". Corrija a causa antes de tentar novamente; não repita os mesmos parâmetros.");
        }
        String nomeArquivo = Json.texto(preparado.dados().path("nome"));
        if (simplificar && !nomeArquivo.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            return ResultadoFerramenta.texto("Cópia simplificada só está disponível para XLSX.");
        boolean automatica = !contexto.idTarefaPlanilha().isBlank();
        if (automatica && (tarefas == null
                || (!"original".equals(Json.texto(entrada.path("destino"))) && !simplificar)
                || !tarefas.autorizada(contexto.idTarefaPlanilha(), contexto.usuarioAutenticado(),
                        Json.texto(entrada.path("id")), nomeArquivo, substituirFormula)))
            return ResultadoFerramenta.texto("Tarefa sem autorização para este XLSX ou operação.");
        boolean identificado = arquivoConstaDoPedido(contexto.pedidoOriginal(), nomeArquivo,
                Json.texto(entrada.path("id")), contexto.ultimoAnexoDeArquivo());
        if (!identificado && apelidoConstaDoPedido(contexto.pedidoOriginal(), nomeArquivo)) {
            if (!preparado.dados().path("apelidoUnico").asBoolean(false))
                return ResultadoFerramenta.texto("Mais de um arquivo corresponde a esse nome falado. "
                        + "Peça ao usuário um detalhe para distinguir o arquivo; nenhuma edição foi feita.");
            identificado = true;
        }
        if (!identificado)
            return ResultadoFerramenta.texto("O arquivo proposto não corresponde ao nome indicado no pedido. "
                    + "Peça ao usuário um nome que o diferencie dos outros; nenhuma edição foi feita.");
        String editId = Json.texto(preparado.dados().path("editId"));
        if (!editId.matches("[0-9a-f-]{36}")) return ResultadoFerramenta.texto("Proposta sem editId válido.");
        String previa = "Prévia de " + nomeArquivo + ":\n"
                + Json.texto(preparado.dados().path("previa"))
                + (preparado.dados().path("previaTruncada").asBoolean(false)
                        ? "\nPrévia truncada; há mais alterações no arquivo." : "")
                + (Json.numero(preparado.dados().path("ocorrenciasSubstituidas")) > 0
                        ? "\nOcorrências substituídas: "
                            + Json.numero(preparado.dados().path("ocorrenciasSubstituidas")) + "." : "");
        // A cópia é arquivo novo e não se desfaz, então a prévia continua indo ao modelo. Na
        // sobrescrita do original ela não decide mais nada — o backup do agente é que garante a
        // volta —, e o que volta é só a frase curta.
        if ("copia".equals(Json.texto(entrada.path("destino"))))
            return fluxo.aplicar("aplicar_edicao", editId, contexto,
                    nome -> previa + "\nCópia criada: " + nome + ".", false);
        long ocorrencias = Json.numero(preparado.dados().path("ocorrenciasSubstituidas"));
        long trechos = ocorrencias > 0 ? ocorrencias : entrada.path("operacoes").size();
        return fluxo.aplicar("aplicar_edicao", editId, contexto,
                nome -> "Atualizei " + nome + " (" + trechos
                        + (trechos == 1 ? " trecho" : " trechos")
                        + "). Se quiser voltar, diga desfaz.", true);
    }
    static boolean arquivoConstaDoPedido(String pedido, String nome, String id, String ultimoAnexo) {
        String texto = Normalizer.normalize(pedido, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String arquivo = Normalizer.normalize(nome, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (contemIdentificador(texto, arquivo)
                || contemIdentificador(texto, id.toLowerCase(Locale.ROOT))) return true;
        if (texto.matches("(?s).*\\.(?:md|txt|json|xlsx)\\b.*")
                || texto.matches("(?s).*[0-9a-f]{8}-[0-9a-f-]{27,}.*")) return false;
        boolean referencia = texto.matches("(?s).*(?:\\besse|\\beste|\\bessa|\\besta)\\s+"
                + "(?:arquivo|documento|texto|relat[oó]rio)\\b.*");
        return referencia && arquivo.equals(Normalizer.normalize(ultimoAnexo, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT));
    }
    /**
     * O nome falado — o nome do arquivo sem a extensão — vale para todo formato editável, e não
     * só para planilha: "tira a Apple do assistentes ia mais usados 2026" é como se fala de um
     * .md. Quem garante que o apelido aponta para um arquivo só é o {@code apelidoUnico} da
     * proposta, conferido contra o catálogo do agente.
     *
     * Pedido que traz extensão ou UUID não entra aqui: ali o usuário nomeou o arquivo, e a
     * comparação exata de {@link #arquivoConstaDoPedido} é que decide.
     */
    static boolean apelidoConstaDoPedido(String pedido, String nome) {
        if (pedido == null || nome == null || nome.lastIndexOf('.') <= 0) return false;
        String original = Normalizer.normalize(pedido, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        if (original.matches("(?s).*\\.(?:md|txt|json|xlsx)\\b.*")
                || original.matches("(?s).*[0-9a-f]{8}-[0-9a-f-]{27,}.*")) return false;
        String apelido = nomeFalado(nome.substring(0, nome.lastIndexOf('.')));
        return apelido.length() >= 5 && (" " + nomeFalado(pedido) + " ")
                .contains(" " + apelido + " ");
    }
    private static String nomeFalado(String nome) {
        return Normalizer.normalize(nome, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
    private static boolean contemIdentificador(String texto, String identificador) {
        if (identificador.isBlank()) return false;
        for (int pos = texto.indexOf(identificador); pos >= 0;
                pos = texto.indexOf(identificador, pos + 1)) {
            int fim = pos + identificador.length();
            if ((pos == 0 || !caractereDeNome(texto.charAt(pos - 1)))
                    && (fim == texto.length() || !caractereDeNome(texto.charAt(fim)))) return true;
        }
        return false;
    }
    private static boolean caractereDeNome(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }
}
