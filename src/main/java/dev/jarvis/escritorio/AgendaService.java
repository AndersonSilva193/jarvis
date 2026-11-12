package dev.jarvis.escritorio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
 */
@Service
public class AgendaService {

    static final int MAX_EVENTOS = 25;
    static final int MAX_TITULO = 80;
    static final int LINHAS_LIDAS = 500;
    private static final Pattern HORA = Pattern.compile("(?<![\\d/])(\\d{1,2})[:h](\\d{2})?(?!\\d)");
    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("EEEE, dd/MM",
            Locale.of("pt", "BR"));

    private final PlanilhaDoPc planilha;
    private final JarvisProperties props;
    private final ZoneId zona;
    private final Clock relogio;

    @Autowired
    public AgendaService(PlanilhaDoPc planilha, JarvisProperties props) {
        this(planilha, props, Clock.systemUTC());
    }

    public AgendaService(PlanilhaDoPc planilha, JarvisProperties props, Clock relogio) {
        this.planilha = planilha;
        this.props = props;
        this.zona = props.zona();
        this.relogio = relogio;
    }

    /** De partida, pela regra da lista de ferramentas estável: não olha se o PC está ligado. */
    public boolean configurado() {
        return props.pcConfigurado();
    }

    public LocalDate hoje() {
        return LocalDate.now(relogio.withZone(zona));
    }

    /** Um compromisso. {@code inicio} vazio é o dia inteiro. */
    public record Compromisso(String inicio, String fim, String titulo) {
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
    record Colunas(int data, int inicio, int fim, int titulo, int local) {
        static Colunas doCabecalho(List<String> cabecalho) {
            int data = achar(cabecalho, "data", "dia");
            int inicio = achar(cabecalho, "inicio", "hora", "horario", "comeca");
            int fim = achar(cabecalho, "fim", "termino", "termina", "ate");
            int titulo = achar(cabecalho, "compromisso", "titulo", "evento", "assunto", "descricao",
                    "reuniao", "o que", "tarefa");
            int local = achar(cabecalho, "local", "onde", "endereco");
            return new Colunas(data, inicio == data ? -1 : inicio, fim, titulo, local);
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
            lista.add(new Compromisso(inicio, fim, cortar(titulo)));
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
