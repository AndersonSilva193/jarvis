package dev.jarvis.ferramentas;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.PcService;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Uma planilha da pasta do Jarvis no PC (a {@code pastaArquivos} do agente), lida pelo nome exato.
 *
 * <p>É o que a agenda e os leads do dia leem. O caminho é o das ferramentas de arquivo —
 * {@code listar_arquivos} acha o ID pelo nome, {@code ler_arquivo} lê —, então valem as mesmas
 * regras: só o que está catalogado na pasta, PC offline responde na hora.
 *
 * <p>Lê o <b>cabeçalho</b> e as <b>últimas</b> linhas: os dois arquivos crescem para baixo, e o
 * que importa (os leads de hoje, a agenda da semana) está no fim. O agente entrega no máximo 100
 * linhas e 30 colunas por chamada, então são até {@link #MAX_CHAMADAS} idas ao PC.
 *
 * <p>XLSX e CSV chegam em formatos diferentes ({@code celulas} com endereço, {@code linhas} como
 * matriz) e saem daqui iguais: texto por célula. A data de uma célula do Excel vem como
 * {@code AAAA-MM-DD}, e com a hora em {@code dataHora} quando o agente é novo o bastante; a
 * hora só sai quando não é meia-noite, para "11/11" não virar "11/11 00:00".
 */
@Component
public class PlanilhaDoPc {

    /** As linhas lidas, sem o cabeçalho, e quantas linhas de dados o arquivo tem ao todo. */
    public record Tabela(String nome, List<String> cabecalho, List<List<String>> linhas, int totalDeLinhas) { }

    /** A ida ao PC, separada para o teste trocar por um agente de mentira. */
    @FunctionalInterface
    public interface Consulta {
        ConsultaArquivosPc.Resposta consultar(String acao, JsonNode entrada) throws Exception;
    }

    static final int LINHAS_POR_CHAMADA_XLSX = 99;
    static final int LINHAS_POR_CHAMADA_CSV = 100;
    static final int MAX_COLUNAS = 30;
    static final int MAX_CHAMADAS = 8;

    private final Consulta consulta;
    private final JsonMapper json;

    @Autowired
    public PlanilhaDoPc(PcService pc, JsonMapper json) {
        this((acao, entrada) -> ConsultaArquivosPc.consultar(pc, acao, entrada, new OuvinteProgresso() { }), json);
    }

    public PlanilhaDoPc(Consulta consulta, JsonMapper json) {
        this.consulta = consulta;
        this.json = json;
    }

    /**
     * Lê o arquivo {@code nome} (exato, sem diferenciar maiúsculas) da raiz da pasta.
     *
     * @param aba        a aba do XLSX; vazio é a primeira. Ignorada no CSV.
     * @param maxLinhas  quantas linhas do fim, no máximo.
     * @throws IOException com uma frase pronta para o dono quando não dá para ler.
     */
    public Tabela ler(String nome, String aba, int maxLinhas) throws Exception {
        JsonNode arquivo = acharPeloNome(nome);
        String id = Json.texto(arquivo.path("id"));
        String formato = Json.texto(arquivo.path("formato")).toLowerCase(Locale.ROOT);
        return switch (formato) {
            case "xlsx" -> lerXlsx(Json.texto(arquivo.path("nome")), id, aba, maxLinhas);
            case "csv" -> lerCsv(Json.texto(arquivo.path("nome")), id, maxLinhas);
            default -> throw new IOException(nome + " não é XLSX nem CSV.");
        };
    }

    private JsonNode acharPeloNome(String nome) throws Exception {
        ObjectNode pedido = json.createObjectNode().put("nome", nome).put("pagina", 1).put("tamanhoPagina", 50);
        JsonNode lista = chamar("listar_arquivos", pedido);
        for (JsonNode item : lista.path("arquivos")) {
            if (nome.equalsIgnoreCase(Json.texto(item.path("nome")))) return item;
        }
        throw new IOException("Não achei o arquivo " + nome + " na pasta do Jarvis no PC.");
    }

    private Tabela lerXlsx(String nome, String id, String aba, int maxLinhas) throws Exception {
        String escolhida = aba == null ? "" : aba.trim();
        if (escolhida.isEmpty()) {
            JsonNode abas = chamar("ler_arquivo", json.createObjectNode().put("id", id)).path("abas");
            if (!abas.isArray() || abas.isEmpty()) throw new IOException(nome + " não tem abas.");
            escolhida = Json.texto(abas.get(0));
        }
        JsonNode topo = chamar("ler_arquivo", intervalo(id, escolhida, 1, 1, MAX_COLUNAS));
        List<String> cabecalho = aparar(linhaDoXlsx(celulasPorEndereco(topo), 1, MAX_COLUNAS));
        if (cabecalho.isEmpty()) return new Tabela(nome, List.of(), List.of(), 0);
        int ultima = (int) Json.numero(topo.path("ultimaLinhaFisica"));
        int total = Math.max(0, ultima - 1);
        int primeira = Math.max(2, ultima - maxLinhas + 1);
        int colunas = cabecalho.size();

        Map<String, JsonNode> celulas = new HashMap<>();
        int linha = primeira;
        for (int chamada = 0; linha <= ultima && chamada < MAX_CHAMADAS; chamada++) {
            int fim = Math.min(ultima, linha + LINHAS_POR_CHAMADA_XLSX - 1);
            JsonNode parte = chamar("ler_arquivo", intervalo(id, escolhida, linha, fim, colunas));
            celulas.putAll(celulasPorEndereco(parte));
            // Resultado grande demais para uma resposta: o agente para e diz onde continuar.
            long continuar = Json.numero(parte.path("continuarLinha"));
            linha = continuar > linha && continuar <= fim ? (int) continuar : fim + 1;
        }
        List<List<String>> linhas = new ArrayList<>();
        for (int l = primeira; l < linha; l++) {
            List<String> valores = linhaDoXlsx(celulas, l, colunas);
            if (valores.stream().anyMatch(v -> !v.isBlank())) linhas.add(valores);
        }
        return new Tabela(nome, cabecalho, linhas, total);
    }

    private ObjectNode intervalo(String id, String aba, int de, int ate, int colunas) {
        return json.createObjectNode().put("id", id).put("aba", aba).put("linhaInicial", de)
                .put("linhaFinal", ate).put("colunaInicial", 1).put("colunaFinal", colunas);
    }

    private static Map<String, JsonNode> celulasPorEndereco(JsonNode dados) {
        Map<String, JsonNode> mapa = new HashMap<>();
        for (JsonNode celula : dados.path("celulas")) {
            String endereco = Json.texto(celula.path("endereco")).trim().toUpperCase(Locale.ROOT);
            if (!endereco.isEmpty()) mapa.put(endereco, celula);
        }
        return mapa;
    }

    private static List<String> linhaDoXlsx(Map<String, JsonNode> celulas, int linha, int colunas) {
        List<String> valores = new ArrayList<>(colunas);
        for (int c = 1; c <= colunas; c++) valores.add(textoDaCelula(celulas.get(letra(c) + linha)));
        return valores;
    }

    /** O texto de uma célula do XLSX como o agente a descreveu. */
    static String textoDaCelula(JsonNode celula) {
        if (celula == null || celula.isMissingNode() || celula.isNull()) return "";
        String dataHora = Json.texto(celula.path("dataHora"));
        if ("data".equals(Json.texto(celula.path("tipo"))) && dataHora.length() >= 16) {
            String hora = dataHora.substring(11, 16);
            String dia = dataHora.substring(0, 10);
            // Hora pura no Excel é uma data em 1899: o que vale ali é só a hora.
            if (dia.startsWith("1899-12-3") || dia.startsWith("1900-01-0")) return hora;
            return "00:00".equals(hora) ? dia : dia + " " + hora;
        }
        JsonNode valor = celula.path("valor");
        if (valor.isNumber()) {
            double numero = valor.doubleValue();
            // Telefone e quantidade salvos como número não podem virar "5541999990000.0".
            if (numero == Math.rint(numero) && Math.abs(numero) < 1e15) return Long.toString((long) numero);
            return Double.toString(numero);
        }
        if (valor.isBoolean()) return valor.booleanValue() ? "sim" : "não";
        return valor.isString() ? valor.stringValue() : "";
    }

    private Tabela lerCsv(String nome, String id, int maxLinhas) throws Exception {
        String separador = ",";
        JsonNode topo = chamar("ler_arquivo", csv(id, 1, 1, separador));
        List<String> cabecalho = primeiraLinha(topo);
        // O Excel em português salva CSV com ponto e vírgula. Um cabeçalho de uma coluna só com
        // ";" dentro é isso; o agente precisa saber antes de separar os campos.
        if (cabecalho.size() == 1 && cabecalho.get(0).contains(";")) {
            separador = ";";
            topo = chamar("ler_arquivo", csv(id, 1, 1, separador));
            cabecalho = primeiraLinha(topo);
        }
        cabecalho = aparar(cabecalho);
        if (cabecalho.isEmpty()) return new Tabela(nome, List.of(), List.of(), 0);
        int ultima = (int) Json.numero(topo.path("totalLinhas"));
        int total = Math.max(0, ultima - 1);
        int primeira = Math.max(2, ultima - maxLinhas + 1);

        List<List<String>> linhas = new ArrayList<>();
        int linha = primeira;
        for (int chamada = 0; linha <= ultima && chamada < MAX_CHAMADAS; chamada++) {
            int quantidade = Math.min(LINHAS_POR_CHAMADA_CSV, ultima - linha + 1);
            JsonNode parte = chamar("ler_arquivo", csv(id, linha, quantidade, separador));
            JsonNode recebidas = parte.path("linhas");
            for (JsonNode campos : recebidas) {
                List<String> valores = new ArrayList<>(cabecalho.size());
                for (int c = 0; c < cabecalho.size(); c++) {
                    valores.add(c < campos.size() ? Json.texto(campos.get(c)) : "");
                }
                if (valores.stream().anyMatch(v -> !v.isBlank())) linhas.add(valores);
            }
            if (recebidas.isEmpty()) break;
            linha += recebidas.size();
        }
        return new Tabela(nome, cabecalho, linhas, total);
    }

    private ObjectNode csv(String id, int de, int quantidade, String separador) {
        return json.createObjectNode().put("id", id).put("linhaInicial", de)
                .put("quantidadeLinhas", quantidade).put("delimitador", separador);
    }

    private static List<String> primeiraLinha(JsonNode dados) {
        List<String> valores = new ArrayList<>();
        JsonNode linhas = dados.path("linhas");
        if (linhas.isArray() && !linhas.isEmpty()) {
            for (JsonNode campo : linhas.get(0)) valores.add(Json.texto(campo));
        }
        return valores;
    }

    /** Tira as colunas vazias do fim do cabeçalho; o resto da tabela segue a largura dele. */
    private static List<String> aparar(List<String> cabecalho) {
        int fim = cabecalho.size();
        while (fim > 0 && cabecalho.get(fim - 1).isBlank()) fim--;
        return new ArrayList<>(cabecalho.subList(0, fim));
    }

    private JsonNode chamar(String acao, ObjectNode pedido) throws Exception {
        ConsultaArquivosPc.Resposta resposta = consulta.consultar(acao, pedido);
        if (!resposta.temDados()) throw new IOException(resposta.recado());
        return resposta.dados();
    }

    static String letra(int coluna) {
        StringBuilder letras = new StringBuilder();
        for (int c = coluna; c > 0; c = (c - 1) / 26) letras.insert(0, (char) ('A' + (c - 1) % 26));
        return letras.toString();
    }
}
