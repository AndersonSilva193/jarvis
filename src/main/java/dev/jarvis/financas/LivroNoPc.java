package dev.jarvis.financas;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.jarvis.ferramentas.PlanilhaDoPc;

/**
 * O livro financeiro como ele fica no {@code financas.csv} da pasta do Jarvis no PC: uma linha por
 * evento, só acrescentando. Remover é mais uma linha, {@code remocao}, apontando o {@code Id}
 * removido na coluna {@code Remove}. O dono só olha o arquivo; quem escreve é o Jarvis.
 *
 * <p>Valor em reais com vírgula e sem separador de milhar ({@code 1234,56}), que o Excel em
 * português lê como número; por dentro tudo continua em centavos.
 */
final class LivroNoPc {

    static final String ARQUIVO = "financas.csv";
    static final List<String> CABECALHO = List.of("Id", "Data", "Tipo", "Valor", "Categoria", "Descricao",
            "Origem", "RegistradoEm", "Remove");
    static final String REMOCAO = "remocao";

    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter DATA_HORA = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private LivroNoPc() {
    }

    static List<String> linha(Lancamento l, ZoneId zona) {
        return List.of(l.id(), l.data().format(DATA), l.tipo().chave(), valor(l.centavos()), l.categoria(),
                l.descricao(), l.origem(), LocalDateTime.ofInstant(l.criadoEm(), zona).format(DATA_HORA), "");
    }

    static List<String> linhaDeRemocao(String idNovo, Lancamento removido, Instant quando, String origem,
            ZoneId zona) {
        return List.of(idNovo, removido.data().format(DATA), REMOCAO, valor(removido.centavos()),
                removido.categoria(), removido.descricao(), origem,
                LocalDateTime.ofInstant(quando, zona).format(DATA_HORA), removido.id());
    }

    /**
     * O inverso de {@link #linha}: os lançamentos de uma tabela lida do PC, com as remoções
     * aplicadas. Linha que não se lê (data, valor ou tipo tortos) fica de fora, sem derrubar o resto.
     */
    static List<Lancamento> importar(PlanilhaDoPc.Tabela tabela, ZoneId zona) {
        Map<String, Integer> colunas = new HashMap<>();
        for (int i = 0; i < tabela.cabecalho().size(); i++)
            colunas.put(tabela.cabecalho().get(i).trim().toLowerCase(Locale.ROOT), i);
        Map<String, Lancamento> lidos = new LinkedHashMap<>();
        List<String[]> remocoes = new ArrayList<>();
        for (List<String> linha : tabela.linhas()) {
            try {
                String id = celula(linha, colunas, "id");
                String tipo = celula(linha, colunas, "tipo").toLowerCase(Locale.ROOT);
                Instant registrado = registradoEm(celula(linha, colunas, "registradoem"), zona);
                if (REMOCAO.equals(tipo)) {
                    remocoes.add(new String[] {celula(linha, colunas, "remove"),
                            registrado == null ? null : registrado.toString()});
                    continue;
                }
                Lancamento.Tipo t = Lancamento.Tipo.porChave(tipo);
                long centavos = centavos(celula(linha, colunas, "valor"));
                LocalDate data = LocalDate.parse(celula(linha, colunas, "data"), DATA);
                if (id.isEmpty() || t == null || centavos <= 0) continue;
                lidos.put(id, new Lancamento(id, t, centavos, FinancasService.categoria(celula(linha, colunas, "categoria")),
                        FinancasService.descricao(celula(linha, colunas, "descricao")), data,
                        registrado == null ? data.atStartOfDay(zona).toInstant() : registrado,
                        celula(linha, colunas, "origem"), null));
            } catch (DateTimeParseException | NumberFormatException e) {
                // linha torta: fica de fora
            }
        }
        for (String[] remocao : remocoes) {
            Lancamento alvo = lidos.get(remocao[0]);
            if (alvo != null && alvo.ativo())
                lidos.put(alvo.id(), alvo.removidoEm(remocao[1] == null ? alvo.criadoEm() : Instant.parse(remocao[1])));
        }
        return new ArrayList<>(lidos.values());
    }

    static String valor(long centavos) {
        return (centavos / 100) + "," + String.format(Locale.ROOT, "%02d", centavos % 100);
    }

    private static long centavos(String valor) {
        String limpo = valor.replace("R$", "").replace(" ", "").replace(".", "").replace(',', '.');
        return Math.round(Double.parseDouble(limpo) * 100);
    }

    private static Instant registradoEm(String texto, ZoneId zona) {
        if (texto.isBlank()) return null;
        try {
            return LocalDateTime.parse(texto, DATA_HORA).atZone(zona).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String celula(List<String> linha, Map<String, Integer> colunas, String nome) {
        Integer i = colunas.get(nome);
        return i == null || i >= linha.size() || linha.get(i) == null ? "" : linha.get(i).trim();
    }
}
