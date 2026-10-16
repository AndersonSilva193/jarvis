package dev.jarvis.ferramentas;

import tools.jackson.databind.node.ObjectNode;

/** Campos estruturados comuns às ferramentas de criação de arquivos. */
final class EsquemaArquivo {
    private EsquemaArquivo() { }

    static void planilha(ObjectNode campos) {
        ObjectNode abas = campos.putObject("abas").put("type", "array")
                .put("minItems", 1).put("maxItems", 5);
        ObjectNode aba = abas.putObject("items").put("type", "object");
        ObjectNode propriedadesAba = aba.putObject("properties");
        propriedadesAba.putObject("nome").put("type", "string").put("maxLength", 31);
        ObjectNode colunas = propriedadesAba.putObject("colunas").put("type", "array")
                .put("minItems", 1).put("maxItems", 30);
        colunas.putObject("items").put("type", "object").putObject("properties")
                .putObject("nome").put("type", "string").put("maxLength", 100);
        ObjectNode linhas = propriedadesAba.putObject("linhas").put("type", "array")
                .put("maxItems", 2000);
        ObjectNode linha = linhas.putObject("items").put("type", "array").put("maxItems", 30);
        ObjectNode celula = linha.putObject("items").put("type", "object");
        ObjectNode propriedadesCelula = celula.putObject("properties");
        ObjectNode tipo = propriedadesCelula.putObject("tipo").put("type", "string");
        var tipos = tipo.putArray("enum");
        for (String nome : new String[] {"texto", "numero", "moeda", "data", "booleano", "formula"})
            tipos.add(nome);
        propriedadesCelula.putObject("valor").put("description", "Valor da célula; datas em yyyy-MM-dd. Fórmulas somente no tipo formula.");
        celula.putArray("required").add("tipo").add("valor");
        aba.putArray("required").add("nome").add("colunas").add("linhas");
    }

    static void fontes(ObjectNode campos) {
        ObjectNode fontes = campos.putObject("fontes").put("type", "array").put("maxItems", 20);
        ObjectNode fonte = fontes.putObject("items").put("type", "object");
        ObjectNode propriedades = fonte.putObject("properties");
        propriedades.putObject("titulo").put("type", "string").put("maxLength", 160);
        propriedades.putObject("url").put("type", "string").put("description", "URL HTTPS da fonte consultada");
        propriedades.putObject("consultadoEm").put("type", "string")
                .put("description", "Data de consulta yyyy-MM-dd");
        fonte.putArray("required").add("titulo").add("url").add("consultadoEm");
    }
}
