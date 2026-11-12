package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * O leitor de planilha da pasta do PC contra um agente de mentira que responde com a forma do
 * agente real (LeitorDeArquivo e CatalogoDeArquivos do jarvis-agente-pc): {@code arquivos} na
 * listagem; {@code abas}, {@code ultimaLinhaFisica} e {@code celulas} com {@code endereco} no XLSX;
 * {@code linhas} e {@code totalLinhas} no CSV, separando pelo {@code delimitador} pedido.
 */
class PlanilhaDoPcTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID_XLSX = "11111111-1111-1111-1111-111111111111";
    private static final String ID_CSV = "22222222-2222-2222-2222-222222222222";

    /** Um PC com agenda.xlsx (aba "Agenda") e leads.csv salvo pelo Excel em português. */
    static final class AgenteDeMentira implements PlanilhaDoPc.Consulta {
        final List<List<Object>> folha = new ArrayList<>();
        final List<String> csv = new ArrayList<>();
        int chamadas;

        @Override
        public ConsultaArquivosPc.Resposta consultar(String acao, JsonNode entrada) {
            chamadas++;
            ObjectNode saida = JSON.createObjectNode();
            if ("listar_arquivos".equals(acao)) {
                String nome = entrada.path("nome").asText().toLowerCase();
                ArrayNode arquivos = saida.putArray("arquivos");
                if ("agenda.xlsx".contains(nome)) arquivos.addObject().put("id", ID_XLSX)
                        .put("nome", "agenda.xlsx").put("formato", "xlsx");
                if ("leads.csv".contains(nome)) arquivos.addObject().put("id", ID_CSV)
                        .put("nome", "leads.csv").put("formato", "csv");
                saida.put("total", arquivos.size());
                return new ConsultaArquivosPc.Resposta(saida, "");
            }
            if (ID_XLSX.equals(entrada.path("id").asText())) return xlsx(entrada, saida);
            return csv(entrada, saida);
        }

        private ConsultaArquivosPc.Resposta xlsx(JsonNode entrada, ObjectNode saida) {
            saida.putArray("abas").add("Agenda").add("Antigo");
            if (!entrada.has("aba")) {
                saida.put("truncado", true).put("continuacao", "escolha uma aba");
                return new ConsultaArquivosPc.Resposta(saida, "");
            }
            saida.put("ultimaLinhaFisica", folha.size());
            ArrayNode celulas = saida.putArray("celulas");
            int de = entrada.path("linhaInicial").asInt(), ate = entrada.path("linhaFinal").asInt();
            int colunas = entrada.path("colunaFinal").asInt();
            assertTrue(ate - de < 100, "o agente recusa mais de 100 linhas");
            assertTrue(colunas <= 30, "o agente recusa mais de 30 colunas");
            for (int l = de; l <= ate; l++) {
                List<Object> linha = l - 1 < folha.size() ? folha.get(l - 1) : List.of();
                for (int c = 1; c <= colunas; c++) {
                    ObjectNode item = celulas.addObject().put("endereco", PlanilhaDoPc.letra(c) + l);
                    Object valor = c - 1 < linha.size() ? linha.get(c - 1) : null;
                    if (valor == null) {
                        item.put("existe", false).put("tipo", "vazio").putNull("valor");
                    } else if (valor instanceof Number n) {
                        item.put("existe", true).put("tipo", "numero").put("valor", n.doubleValue());
                    } else if (valor instanceof String s && s.startsWith("@")) {
                        // "@2026-11-11T14:00": uma célula de data do Excel, como o agente a descreve.
                        item.put("existe", true).put("tipo", "data").put("valor", s.substring(1, 11))
                                .put("dataHora", s.substring(1));
                    } else {
                        item.put("existe", true).put("tipo", "texto").put("valor", valor.toString());
                    }
                }
            }
            if (ate < folha.size()) saida.put("truncado", true).put("continuarLinha", ate + 1);
            return new ConsultaArquivosPc.Resposta(saida, "");
        }

        private ConsultaArquivosPc.Resposta csv(JsonNode entrada, ObjectNode saida) {
            String separador = entrada.path("delimitador").asText(",");
            int de = entrada.path("linhaInicial").asInt(1), quantidade = entrada.path("quantidadeLinhas").asInt(20);
            assertTrue(quantidade <= 100, "o agente recusa mais de 100 linhas de CSV");
            ArrayNode linhas = saida.putArray("linhas");
            for (int n = de; n < de + quantidade && n <= csv.size(); n++) {
                ArrayNode campos = linhas.addArray();
                for (String campo : csv.get(n - 1).split(java.util.regex.Pattern.quote(separador), -1)) campos.add(campo);
            }
            saida.put("totalLinhas", csv.size());
            return new ConsultaArquivosPc.Resposta(saida, "");
        }
    }

    @Test
    @DisplayName("XLSX: primeira aba, cabeçalho e as últimas linhas, com data, hora e número inteiros")
    void xlsx() throws Exception {
        var agente = new AgenteDeMentira();
        agente.folha.add(List.of("Data", "Hora", "Compromisso", "Telefone", ""));
        for (int i = 0; i < 250; i++) agente.folha.add(List.of("@2026-10-01T00:00", "", "antigo " + i));
        agente.folha.add(List.of("@2026-11-11T00:00", "@1899-12-31T14:00", "Dentista", 5541999990000L));
        agente.folha.add(List.of("@2026-11-11T09:30", "", "Café"));

        var tabela = new PlanilhaDoPc(agente, JSON).ler("agenda.xlsx", "", 120);
        assertEquals("agenda.xlsx", tabela.nome());
        assertEquals(List.of("Data", "Hora", "Compromisso", "Telefone"), tabela.cabecalho());
        assertEquals(252, tabela.totalDeLinhas());
        assertEquals(120, tabela.linhas().size());
        assertEquals(List.of("2026-11-11", "14:00", "Dentista", "5541999990000"),
                tabela.linhas().get(118));
        assertEquals(List.of("2026-11-11 09:30", "", "Café", ""), tabela.linhas().get(119));
        // listar + abas + cabeçalho + duas faixas de no máximo 99 linhas.
        assertEquals(5, agente.chamadas);
    }

    @Test
    @DisplayName("CSV do Excel em português: percebe o ponto e vírgula pelo cabeçalho")
    void csvComPontoEVirgula() throws Exception {
        var agente = new AgenteDeMentira();
        agente.csv.add("Data;Empresa;WhatsApp");
        for (int i = 0; i < 150; i++) agente.csv.add("01/11/2026;Loja " + i + ";41 9999-" + i);
        agente.csv.add("11/11/2026;Academia Força;41 99999-0000");
        agente.csv.add(";;");

        var tabela = new PlanilhaDoPc(agente, JSON).ler("LEADS.csv", "", 30);
        assertEquals(List.of("Data", "Empresa", "WhatsApp"), tabela.cabecalho());
        assertEquals(152, tabela.totalDeLinhas());
        assertEquals(29, tabela.linhas().size(), "a linha vazia do fim não conta");
        assertEquals(List.of("11/11/2026", "Academia Força", "41 99999-0000"), tabela.linhas().get(28));
    }

    @Test
    @DisplayName("nome exato: arquivo parecido não serve, e o recado do PC chega como está")
    void naoAcha() {
        var agente = new AgenteDeMentira();
        var leitor = new PlanilhaDoPc(agente, JSON);
        var erro = assertThrows(IOException.class, () -> leitor.ler("agenda", "", 10));
        assertEquals("Não achei o arquivo agenda na pasta do Jarvis no PC.", erro.getMessage());

        var offline = new PlanilhaDoPc((acao, entrada) -> new ConsultaArquivosPc.Resposta(null,
                "O PC está offline; os arquivos originais locais estão indisponíveis."), JSON);
        assertEquals("O PC está offline; os arquivos originais locais estão indisponíveis.",
                assertThrows(IOException.class, () -> offline.ler("leads.csv", "", 10)).getMessage());
    }

    @Test
    @DisplayName("hora pura do Excel sai como hora; meia-noite não aparece na data")
    void celulas() {
        assertEquals("14:00", PlanilhaDoPc.textoDaCelula(JSON.createObjectNode().put("tipo", "data")
                .put("valor", "1899-12-31").put("dataHora", "1899-12-31T14:00")));
        assertEquals("2026-11-11", PlanilhaDoPc.textoDaCelula(JSON.createObjectNode().put("tipo", "data")
                .put("valor", "2026-11-11").put("dataHora", "2026-11-11T00:00")));
        assertEquals("2026-11-11", PlanilhaDoPc.textoDaCelula(JSON.createObjectNode().put("tipo", "data")
                .put("valor", "2026-11-11")));
        assertEquals("12.5", PlanilhaDoPc.textoDaCelula(JSON.createObjectNode().put("valor", 12.5)));
        assertEquals("AA", PlanilhaDoPc.letra(27));
    }
}
