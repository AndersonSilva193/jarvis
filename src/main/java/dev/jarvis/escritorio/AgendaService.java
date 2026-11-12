package dev.jarvis.escritorio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.PlanilhaDoPc;
import dev.jarvis.util.Texto;

/**
 * Os compromissos de um dia, lidos de uma planilha na pasta do Jarvis no PC
 * ({@code AGENDA_ARQUIVO}, XLSX ou CSV, padrão {@code agenda.csv}).
 *
 * <p>Uma linha por compromisso. As colunas são achadas pelo <b>cabeçalho</b>: data (obrigatória),
 * início ou hora, fim, o compromisso (título, evento, assunto) e o local. A data aceita
 * {@code dd/MM/aaaa}, {@code dd/MM} e {@code AAAA-MM-DD}, e pode trazer a hora junto; sem hora, o
 * compromisso é do dia inteiro. Só as últimas {@link #LINHAS_LIDAS} linhas são lidas.
 *
 * <p>O texto de uma linha é dado, não instrução: sai sem caractere de controle, sem os sinais
 * {@code <<<}/{@code >>>} e cortado, e quem lê é o modelo como resultado de ferramenta.
 *
 * <p>A agenda também é escrita: {@link #registrar}, {@link #alterar} e {@link #cancelar} mandam a
 * linha ao agente do PC pela {@link FilaDoPc}, com a coluna {@code Id} que identifica cada
 * compromisso. O dono pode editar o arquivo à mão; linha sem {@code Id} ganha um na próxima escrita.
 */
@Service
public class AgendaService {

    static final int MAX_EVENTOS = 25;
    static final int MAX_TITULO = 80;
    static final int LINHAS_LIDAS = 500;
    private static final Pattern HORA = Pattern.compile("(?<![\\d/])(\\d{1,2})[:h](\\d{2})?(?!\\d)");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE, dd/MM",
            Locale.of("pt", "BR"));

    /** O cabeçalho do agenda.csv que o Jarvis cria. */
    public static final List<String> CABECALHO_CSV = List.of("Id", "Data", "Início", "Fim", "Compromisso", "Local");
    static final int MAX_LOCAL = 60;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,40}");
    private static final DateTimeFormatter DATA_CSV = DateTimeFormatter.ofPattern("dd/MM/uuuu")
            .withResolverStyle(java.time.format.ResolverStyle.STRICT);
    /** Campo que o agente pede para alterar → coluna do agenda.csv. */
    private static final Map<String, String> CAMPOS = Map.of("data", "Data", "inicio", "Início",
            "fim", "Fim", "compromisso", "Compromisso", "local", "Local");

    private final PlanilhaDoPc planilha;
    private final JarvisProperties props;
    private final ZoneId zona;
    private final Clock relogio;
    /** Nulo: só leitura (quem monta à mão sem PC). */
    private final FilaDoPc fila;

    @Autowired
    public AgendaService(PlanilhaDoPc planilha, JarvisProperties props, FilaDoPc fila) {
        this(planilha, props, Clock.systemUTC(), fila);
    }

    public AgendaService(PlanilhaDoPc planilha, JarvisProperties props, Clock relogio) {
        this(planilha, props, relogio, null);
    }

    public AgendaService(PlanilhaDoPc planilha, JarvisProperties props, Clock relogio, FilaDoPc fila) {
        this.planilha = planilha;
        this.props = props;
        this.zona = props.zona();
        this.relogio = relogio;
        this.fila = fila;
    }

    // ---------------------------------------------------------------- escrita

    /** O compromisso escrito e como ele foi para o agenda.csv do PC. */
    public record Gravacao(String id, EscritaNoPc.Estado estado) { }

    /**
     * Marca um compromisso.
     *
     * @param data   {@code dd/MM/aaaa} ou {@code AAAA-MM-DD}
     * @param inicio e {@code fim}: hora ou vazio (sem início é o dia inteiro)
     * @throws IllegalArgumentException com a frase do que corrigir; nada vai ao PC
     */
    public Gravacao registrar(String data, String inicio, String fim, String titulo, String local) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        List<String> linha = List.of(id, dataDaAgenda(data), horaOpcional(inicio, "início"),
                horaOpcional(fim, "fim"), titulo(titulo), localDoCompromisso(local));
        var dados = JSON.createObjectNode();
        var cabecalho = dados.putArray("cabecalho");
        CABECALHO_CSV.forEach(cabecalho::add);
        var celulas = dados.putArray("linhas").addArray();
        linha.forEach(celulas::add);
        return new Gravacao(id, escrever("acrescentar_linhas", dados));
    }

    /** Muda só os campos pedidos ({@code data}, {@code inicio}, {@code fim}, {@code compromisso}, {@code local}). */
    public Gravacao alterar(String id, Map<String, String> campos) {
        String alvo = idValido(id);
        if (campos == null || campos.isEmpty()) throw new IllegalArgumentException("diga o que mudar no compromisso.");
        var dados = JSON.createObjectNode().put("id", alvo);
        var valores = dados.putObject("valores");
        Map<String, String> ordenados = new LinkedHashMap<>();
        for (Map.Entry<String, String> campo : campos.entrySet()) {
            String chave = campo.getKey() == null ? "" : campo.getKey().trim().toLowerCase(Locale.ROOT);
            String coluna = CAMPOS.get(chave);
            if (coluna == null)
                throw new IllegalArgumentException("não sei mudar '" + campo.getKey() + "'; use data, inicio, fim, compromisso ou local.");
            String valor = campo.getValue() == null ? "" : campo.getValue();
            ordenados.put(coluna, switch (chave) {
                case "data" -> dataDaAgenda(valor);
                case "inicio" -> horaOpcional(valor, "início");
                case "fim" -> horaOpcional(valor, "fim");
                case "compromisso" -> titulo(valor);
                default -> localDoCompromisso(valor);
            });
        }
        ordenados.forEach(valores::put);
        return new Gravacao(alvo, escrever("alterar_linha", dados));
    }

    public Gravacao cancelar(String id) {
        String alvo = idValido(id);
        return new Gravacao(alvo, escrever("remover_linha", JSON.createObjectNode().put("id", alvo)));
    }

    private EscritaNoPc.Estado escrever(String acao, tools.jackson.databind.node.ObjectNode dados) {
        String arquivo = props.arquivoDaAgenda();
        if (!arquivo.toLowerCase(Locale.ROOT).endsWith(".csv"))
            throw new IllegalArgumentException("a agenda (" + arquivo + ") não é CSV; o Jarvis só escreve em agenda CSV.");
        if (fila == null) throw new IllegalStateException("agenda sem escrita: o PC não está ligado a este Jarvis.");
        return fila.gravarOuEnfileirar(new PedidoDeEscrita(UUID.randomUUID().toString(), acao, arquivo, dados,
                relogio.instant().toString())).estado();
    }

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();

    private static String idValido(String id) {
        String limpo = id == null ? "" : id.trim();
        if (!ID.matcher(limpo).matches()) throw new IllegalArgumentException("o id do compromisso é o que a agenda_de_hoje mostra, como a1b2c3d4.");
        return limpo;
    }

    private String dataDaAgenda(String data) {
        String bruto = data == null ? "" : data.trim();
        try {
            LocalDate dia = bruto.matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(bruto)
                    : LocalDate.parse(bruto, DATA_CSV);
            return dia.format(DATA_CSV);
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("a data tem de ser dd/MM/aaaa ou AAAA-MM-DD, como 12/11/2026.");
        }
    }

    private static String horaOpcional(String valor, String nome) {
        if (valor == null || valor.isBlank()) return "";
        String lida = hora(valor);
        if (lida.isEmpty()) throw new IllegalArgumentException("o " + nome + " tem de ser uma hora, como 14:00.");
        return lida;
    }

    private static String titulo(String valor) {
        String limpo = limpar(valor);
        if (limpo.isEmpty()) throw new IllegalArgumentException("diga o que é o compromisso.");
        if (limpo.length() > MAX_TITULO)
            throw new IllegalArgumentException("o compromisso passa de " + MAX_TITULO + " caracteres; resuma.");
        return limpo;
    }

    private static String localDoCompromisso(String valor) {
        String limpo = limpar(valor);
        if (limpo.length() > MAX_LOCAL)
            throw new IllegalArgumentException("o local passa de " + MAX_LOCAL + " caracteres; resuma.");
        return limpo;
    }

    // ---------------------------------------------------------------- leitura

    /** De partida, pela regra da lista de ferramentas estável: não olha se o PC está ligado. */
    public boolean configurado() {
        return props.pcConfigurado();
    }

    public LocalDate hoje() {
        return LocalDate.now(relogio.withZone(zona));
    }

    /** Um compromisso. {@code inicio} vazio é o dia inteiro. */
    public record Compromisso(String id, String inicio, String fim, String titulo) {
        public Compromisso(String inicio, String fim, String titulo) {
            this("", inicio, fim, titulo);
        }

        boolean diaInteiro() {
            return inicio.isEmpty();
        }
    }

    public List<Compromisso> doDia(LocalDate dia) throws Exception {
        PlanilhaDoPc.Tabela tabela = planilha.ler(props.arquivoDaAgenda(), props.abaDaAgenda(), LINHAS_LIDAS);
        return doDia(tabela.cabecalho(), tabela.linhas(), dia);
    }

    /** O texto que vai ao modelo ou ao agente. */
    public String textoDoDia(LocalDate dia) throws Exception {
        PlanilhaDoPc.Tabela tabela = planilha.ler(props.arquivoDaAgenda(), props.abaDaAgenda(), LINHAS_LIDAS);
        if (Colunas.doCabecalho(tabela.cabecalho()).data() < 0) {
            return "A agenda (" + tabela.nome() + ") não tem coluna de data; não sei de que dia é cada linha.";
        }
        return texto(dia, doDia(tabela.cabecalho(), tabela.linhas(), dia), hoje());
    }

    /** As colunas que importam. -1 é coluna que a planilha não tem. */
    record Colunas(int data, int inicio, int fim, int titulo, int local, int id) {
        static Colunas doCabecalho(List<String> cabecalho) {
            int id = -1;
            for (int i = 0; i < cabecalho.size(); i++) if (Texto.normalizar(cabecalho.get(i)).equals("id")) id = i;
            int data = achar(cabecalho, "data", "dia");
            int inicio = achar(cabecalho, "inicio", "hora", "horario", "comeca");
            int fim = achar(cabecalho, "fim", "termino", "termina", "ate");
            int titulo = achar(cabecalho, "compromisso", "titulo", "evento", "assunto", "descricao",
                    "reuniao", "o que", "tarefa");
            int local = achar(cabecalho, "local", "onde", "endereco");
            return new Colunas(data, inicio == data ? -1 : inicio, fim, titulo, local, id);
        }

        private static int achar(List<String> cabecalho, String... nomes) {
            for (String nome : nomes) {
                for (int i = 0; i < cabecalho.size(); i++) {
                    if (Texto.normalizar(cabecalho.get(i)).contains(nome)) return i;
                }
            }
            return -1;
        }
    }

    static List<Compromisso> doDia(List<String> cabecalho, List<List<String>> linhas, LocalDate dia) {
        Colunas colunas = Colunas.doCabecalho(cabecalho);
        if (colunas.data() < 0) return List.of();
        List<Compromisso> lista = new ArrayList<>();
        for (List<String> linha : linhas) {
            String bruto = celula(linha, colunas.data());
            if (!dia.equals(LeadsService.data(bruto, dia))) continue;
            // A hora pode estar na própria coluna de data ("11/11/2026 14:00").
            String inicio = hora(celula(linha, colunas.inicio()));
            if (inicio.isEmpty()) inicio = hora(depoisDaData(bruto));
            String fim = hora(celula(linha, colunas.fim()));
            String titulo = limpar(celula(linha, colunas.titulo()));
            String local = limpar(celula(linha, colunas.local()));
            if (titulo.isEmpty()) titulo = "(sem título)";
            if (!local.isEmpty()) titulo = titulo + " (" + local + ")";
            lista.add(new Compromisso(limpar(celula(linha, colunas.id())), inicio, fim, cortar(titulo)));
        }
        lista.sort(Comparator.comparing((Compromisso c) -> !c.diaInteiro())
                .thenComparing(Compromisso::inicio));
        return lista.size() > MAX_EVENTOS ? lista.subList(0, MAX_EVENTOS) : lista;
    }

    static String texto(LocalDate dia, List<Compromisso> compromissos, LocalDate hoje) {
        String quando = dia.equals(hoje) ? "Hoje" : dia.equals(hoje.plusDays(1)) ? "Amanhã"
                : dia.format(DIA);
        if (compromissos.isEmpty()) {
            return quando + " a agenda está livre.";
        }
        StringBuilder texto = new StringBuilder(quando).append(", ")
                .append(compromissos.size() == 1 ? "1 compromisso:" : compromissos.size() + " compromissos:");
        for (Compromisso c : compromissos) {
            texto.append("\n- ");
            if (c.diaInteiro()) {
                texto.append("dia inteiro");
            } else {
                texto.append(c.inicio());
                if (!c.fim().isEmpty()) texto.append("–").append(c.fim());
            }
            texto.append(": ").append(c.titulo());
            if (!c.id().isEmpty()) texto.append(" (id ").append(c.id()).append(")");
        }
        return texto.toString();
    }

    /** "14:00", "14h", "9h30", "14:00:00" → "14:00". Vazio quando não há hora válida. */
    static String hora(String bruto) {
        Matcher m = HORA.matcher(bruto == null ? "" : bruto);
        if (!m.find()) return "";
        int h = Integer.parseInt(m.group(1));
        int min = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return "";
        return String.format(Locale.ROOT, "%02d:%02d", h, min);
    }

    private static String depoisDaData(String bruto) {
        String valor = bruto == null ? "" : bruto.trim();
        int espaco = valor.indexOf(' ');
        int t = valor.indexOf('T');
        int corte = espaco > 0 ? espaco : t > 0 ? t : -1;
        return corte > 0 ? valor.substring(corte + 1) : "";
    }

    private static String celula(List<String> linha, int coluna) {
        return coluna >= 0 && coluna < linha.size() ? linha.get(coluna) : "";
    }

    static String limpar(String bruto) {
        return (bruto == null ? "" : bruto).replaceAll("\\p{Cntrl}", " ").replace("<<<", " ")
                .replace(">>>", " ").replaceAll("\\s+", " ").trim();
    }

    private static String cortar(String texto) {
        return texto.length() > MAX_TITULO ? texto.substring(0, MAX_TITULO - 1).trim() + "…" : texto;
    }
}
