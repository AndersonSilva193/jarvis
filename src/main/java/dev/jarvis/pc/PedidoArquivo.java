package dev.jarvis.pc;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;

/** Contrato limitado, também revalidado pelo agente antes de gravar. */
public final class PedidoArquivo {
    public static final int LIMITE_MARKDOWN = 256 * 1024;
    public static final int LIMITE_JSON = 1024 * 1024;
    private static final Set<String> DOCUMENTOS = Set.of("md", "txt", "docx", "pdf");
    private static final Set<String> PLANILHAS = Set.of("xlsx", "csv");
    private static final Set<String> TIPOS = Set.of("texto", "numero", "moeda", "data", "booleano", "formula");
    private PedidoArquivo() { }

    public static String validar(JsonNode pedido) {
        if (pedido == null || !pedido.isObject()) return "Informe os dados do arquivo.";
        String nome = Json.texto(pedido.path("nome"));
        String formato = Json.texto(pedido.path("formato"));
        if (nome.isBlank() || nome.length() > 120 || nome.contains("/") || nome.contains("\\")
                || nome.contains("..") || nome.contains(":") || nome.startsWith("~"))
            return "Nome inválido: informe somente o nome, sem caminho.";
        if (!DOCUMENTOS.contains(formato) && !PLANILHAS.contains(formato))
            return "Formato aceito: md, txt, docx, pdf, xlsx ou csv.";
        if (DOCUMENTOS.contains(formato)) {
            String conteudo = Json.texto(pedido.path("conteudo"));
            if (conteudo.getBytes(StandardCharsets.UTF_8).length > LIMITE_MARKDOWN)
                return "Markdown acima de 256 KiB.";
            if (conteudo.matches("(?s).*(!\\[[^]]*]\\([^)]*\\)|<[^>]+>|\\{\\{.*?}}).*"))
                return "Imagens, HTML e templates não são aceitos no documento.";
        } else {
            if (pedido.toString().getBytes(StandardCharsets.UTF_8).length > LIMITE_JSON)
                return "Dados da planilha acima de 1 MiB.";
            JsonNode abas = pedido.path("abas");
            if (!abas.isArray() || abas.isEmpty() || abas.size() > (formato.equals("csv") ? 1 : 5))
                return "Informe de 1 a 5 abas; CSV aceita uma única tabela.";
            int celulas = 0;
            Set<String> nomes = new HashSet<>();
            for (JsonNode aba : abas) {
                String nomeAba = Json.texto(aba.path("nome"));
                if (nomeAba.isBlank() || nomeAba.length() > 31 || nomeAba.matches(".*[\\[\\]:*?/\\\\].*" )
                        || !nomes.add(nomeAba.toLowerCase(java.util.Locale.ROOT))) return "Nome de aba inválido ou repetido.";
                JsonNode colunas = aba.path("colunas");
                JsonNode linhas = aba.path("linhas");
                if (!colunas.isArray() || colunas.isEmpty() || colunas.size() > 30
                        || !linhas.isArray() || linhas.size() > 2000) return "Limite de linhas ou colunas excedido.";
                celulas += colunas.size() * (linhas.size() + 1);
                for (JsonNode coluna : colunas) {
                    String titulo = Json.texto(coluna.path("nome"));
                    if (titulo.isBlank() || titulo.length() > 100) return "Cabeçalho inválido.";
                }
                for (JsonNode linha : linhas) {
                    if (!linha.isArray() || linha.size() > colunas.size()) return "Linha inválida.";
                    for (JsonNode celula : linha) {
                        String tipo = Json.texto(celula.path("tipo"));
                        if (!TIPOS.contains(tipo) || (formato.equals("csv") && tipo.equals("formula")))
                            return "Tipo de célula inválido.";
                        if (celula.path("valor").toString().length() > 4096) return "Célula grande demais.";
                        String valor = celula.path("valor").isTextual()
                                ? celula.path("valor").stringValue() : celula.path("valor").toString();
                        if (tipo.equals("numero") || tipo.equals("moeda")) {
                            try {
                                var numero = new java.math.BigDecimal(valor.replace(',', '.'));
                                if (numero.abs().compareTo(new java.math.BigDecimal("1000000000")) > 0
                                        || (tipo.equals("moeda") && numero.scale() > 2))
                                    return "Número ou moeda fora do limite.";
                            } catch (RuntimeException e) { return "Número inválido."; }
                        }
                        if (tipo.equals("booleano") && !Set.of("true", "false").contains(valor))
                            return "Booleano deve ser true ou false.";
                        if (tipo.equals("formula") && !formulaValida(Json.texto(celula.path("valor"))))
                            return "Fórmula não permitida.";
                        if (tipo.equals("data")) {
                            try { LocalDate.parse(Json.texto(celula.path("valor"))); }
                            catch (RuntimeException e) { return "Data deve ser ISO yyyy-MM-dd."; }
                        }
                    }
                }
            }
            if (celulas > 20_000) return "Planilha acima de 20.000 células.";
        }
        JsonNode fontes = pedido.path("fontes");
        if (!fontes.isMissingNode() && !fontes.isNull()) {
            if (!fontes.isArray() || fontes.size() > 20) return "Fontes inválidas.";
            for (JsonNode fonte : fontes) {
                try {
                    URI uri = URI.create(Json.texto(fonte.path("url")));
                    LocalDate.parse(Json.texto(fonte.path("consultadoEm")));
                    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                            || uri.getUserInfo() != null || Json.texto(fonte.path("titulo")).isBlank()
                            || Json.texto(fonte.path("titulo")).length() > 160
                            || Json.texto(fonte.path("titulo")).matches("(?s).*[<>\\r\\n!\\[\\]{}].*"))
                        return "Fonte precisa de título, URL HTTPS e data de consulta.";
                } catch (RuntimeException e) { return "Fonte precisa de título, URL HTTPS e data de consulta."; }
            }
        }
        if (pedido.path("pesquisaAtual").asBoolean(false) && (fontes == null || fontes.isEmpty()))
            return "Pesquisa atual exige fontes e data de consulta.";
        return "";
    }

    private static boolean formulaValida(String formula) {
        return formula.length() <= 128 && formula.matches("(?i)^=?[A-Z0-9:(),.+\\-*/ ]+$")
                && !formula.matches(".*[\\[\\]!'\";{}].*")
                && java.util.regex.Pattern.compile("[A-Z_]+(?=\\()", java.util.regex.Pattern.CASE_INSENSITIVE)
                        .matcher(formula).results().allMatch(r -> Set.of("SUM", "AVERAGE", "MIN", "MAX", "COUNT")
                                .contains(r.group().toUpperCase(java.util.Locale.ROOT)));
    }
}
