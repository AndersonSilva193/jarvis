package dev.jarvis.escritorio;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ferramentas.PlanilhaDoPc;
import dev.jarvis.util.Texto;

/**
 * Os leads da prospecção, lidos de uma planilha na pasta do Jarvis no PC ({@code LEADS_ARQUIVO},
 * XLSX ou CSV, padrão {@code leads.csv}).
 *
 * <p>Quem escreve o arquivo é o agente de prospecção, não o Jarvis, e o formato dele pode mudar.
 * Então as colunas são achadas pelo <b>cabeçalho</b> (nome, segmento, telefone, cidade, status,
 * data), e não pela posição. "Do dia" são as linhas com a data de hoje na coluna de data; sem essa
 * coluna, ou sem nada de hoje, saem as últimas linhas, e o texto diz qual dos dois foi. Só as
 * últimas {@link #LINHAS_LIDAS} linhas do arquivo são lidas: lead novo entra no fim.
 *
 * <p>Os valores são dados de terceiros (nomes e telefones de empresas): saem limpos e cortados.
 */
@Service
public class LeadsService {

    static final int MAX_LEADS = 15;
    static final int MAX_CAMPO = 60;
    static final int LINHAS_LIDAS = 300;
    private static final Pattern DATA_BR = Pattern.compile("^(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?");

    private final PlanilhaDoPc planilha;
    private final JarvisProperties props;
    private final ZoneId zona;
    private final Clock relogio;

    @Autowired
    public LeadsService(PlanilhaDoPc planilha, JarvisProperties props) {
        this(planilha, props, Clock.systemUTC());
    }

    public LeadsService(PlanilhaDoPc planilha, JarvisProperties props, Clock relogio) {
        this.planilha = planilha;
        this.props = props;
        this.zona = props.zona();
        this.relogio = relogio;
    }

    /** De partida: o controle do PC configurado. PC desligado é a leitura que avisa. */
    public boolean configurado() {
        return props.pcConfigurado();
    }

    public String textoDoDia() throws Exception {
        PlanilhaDoPc.Tabela tabela = planilha.ler(props.arquivoDeLeads(), props.abaDeLeads(), LINHAS_LIDAS);
        List<List<String>> linhas = new ArrayList<>();
        linhas.add(tabela.cabecalho());
        linhas.addAll(tabela.linhas());
        return texto(linhas, tabela.totalDeLinhas(), LocalDate.now(relogio.withZone(zona)));
    }

    /** As colunas que importam, achadas pelo cabeçalho. -1 é coluna que a planilha não tem. */
    record Colunas(int nome, int segmento, int telefone, int cidade, int status, int data) {

        static Colunas doCabecalho(List<String> cabecalho) {
            return new Colunas(achar(cabecalho, "nome", "empresa", "negocio", "estabelecimento", "lead"),
                    achar(cabecalho, "segmento", "categoria", "nicho", "ramo", "tipo"),
                    achar(cabecalho, "whatsapp", "telefone", "celular", "fone", "contato"),
                    achar(cabecalho, "cidade", "bairro", "regiao", "endereco", "local"),
                    achar(cabecalho, "status", "situacao", "etapa"),
                    achar(cabecalho, "data", "dia", "quando", "encontrado", "prospectado"));
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

    static String texto(List<List<String>> linhas, LocalDate hoje) {
        return texto(linhas, linhas.size() - 1, hoje);
    }

    /**
     * @param linhas o cabeçalho e as linhas lidas (as últimas do arquivo).
     * @param total  quantas linhas de dados o arquivo tem, lidas ou não.
     */
    static String texto(List<List<String>> linhas, int total, LocalDate hoje) {
        if (linhas.size() < 2) {
            return "A planilha de leads está vazia.";
        }
        Colunas colunas = Colunas.doCabecalho(linhas.get(0));
        List<List<String>> dados = linhas.subList(1, linhas.size()).stream()
                .filter(l -> l.stream().anyMatch(c -> !c.isBlank()))
                .toList();
        List<List<String>> deHoje = colunas.data() < 0 ? List.of()
                : dados.stream().filter(l -> hoje.equals(data(celula(l, colunas.data()), hoje))).toList();

        List<List<String>> escolhidos;
        String titulo;
        if (!deHoje.isEmpty()) {
            escolhidos = deHoje.subList(Math.max(0, deHoje.size() - MAX_LEADS), deHoje.size());
            titulo = "Leads de hoje (" + deHoje.size() + "):";
        } else {
            escolhidos = dados.subList(Math.max(0, dados.size() - MAX_LEADS), dados.size());
            titulo = (colunas.data() < 0 ? "A planilha não tem coluna de data; os últimos "
                    : "Nenhum lead com a data de hoje; os últimos ") + escolhidos.size() + " de "
                    + Math.max(total, dados.size()) + ":";
        }
        StringBuilder texto = new StringBuilder(titulo);
        int numero = 1;
        for (List<String> linha : escolhidos) {
            List<String> partes = new ArrayList<>();
            for (int coluna : new int[] {colunas.nome(), colunas.segmento(), colunas.telefone(),
                    colunas.cidade(), colunas.status()}) {
                String valor = limpar(celula(linha, coluna));
                if (!valor.isEmpty()) partes.add(valor);
            }
            if (partes.isEmpty()) partes.add(limpar(String.join(" ", linha)));
            texto.append('\n').append(numero++).append(". ").append(String.join(" — ", partes));
        }
        return texto.toString();
    }

    private static String celula(List<String> linha, int coluna) {
        return coluna >= 0 && coluna < linha.size() ? linha.get(coluna) : "";
    }

    /** dd/MM/aaaa, dd/MM/aa, dd/MM (ano de hoje) ou AAAA-MM-DD. Nulo quando não é data. */
    static LocalDate data(String bruto, LocalDate hoje) {
        String valor = bruto == null ? "" : bruto.trim();
        if (valor.isEmpty()) return null;
        try {
            if (valor.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return LocalDate.parse(valor.substring(0, 10));
            Matcher m = DATA_BR.matcher(valor);
            if (!m.find()) return null;
            int ano = m.group(3) == null ? hoje.getYear()
                    : m.group(3).length() == 2 ? 2000 + Integer.parseInt(m.group(3))
                    : Integer.parseInt(m.group(3));
            return LocalDate.of(ano, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    static String limpar(String bruto) {
        String limpo = (bruto == null ? "" : bruto).replaceAll("\\p{Cntrl}", " ")
                .replace("<<<", " ").replace(">>>", " ").replaceAll("\\s+", " ").trim();
        return limpo.length() > MAX_CAMPO ? limpo.substring(0, MAX_CAMPO - 1).trim() + "…" : limpo;
    }
}
