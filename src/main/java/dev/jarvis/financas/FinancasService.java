package dev.jarvis.financas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.util.ArquivoAtomico;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Os gastos e ganhos do dono, num lugar só.
 *
 * <p>É o mesmo livro para os dois lados: o Jarvis lança e consulta pela conversa
 * ({@code registrar_lancamento}, {@code resumo_financeiro}) e o agente financeiro do escritório
 * pelo servidor MCP. Por isso os números batem — não há duas contas para conciliar.
 *
 * <p>Mora em {@code DIARIO_PASTA/financas.json}, com a troca atômica e o {@code .bak} do
 * {@link ArquivoAtomico}, como o {@code concessoes.json}: pasta derivada, sem configuração nova. O
 * expurgo do diário não o toca, porque só apaga o que casa o nome de um dia. Gravação que falha
 * desfaz a mudança em memória e lança: o lançamento não pode ser confirmado se não foi para o disco.
 *
 * <p>Toda entrada de fora (valor, categoria, descrição, data) é conferida aqui, e não em quem chama:
 * são três portas (duas ferramentas e o MCP) para o mesmo livro, e a regra tem de ser uma.
 */
@Component
public class FinancasService {

    private static final Logger log = LoggerFactory.getLogger(FinancasService.class);

    public static final String NOME_DO_ARQUIVO = "financas.json";
    /** R$ 1.000.000,00: acima disso é engano de digitação, não lançamento. */
    static final long MAX_CENTAVOS = 100_000_000L;
    static final int MAX_LANCAMENTOS = 20_000;
    static final int MAX_DESCRICAO = 120;
    static final int MAX_CATEGORIA = 30;
    /** Lançamento retroativo vale até isto; mais antigo é quase sempre ano digitado errado. */
    static final int DIAS_NO_PASSADO = 400;
    static final int MAX_CATEGORIAS_NO_RESUMO = 8;
    static final int ULTIMOS_NO_RESUMO = 5;
    private static final long MAX_BYTES = 16L * 1024 * 1024;
    private static final String SEM_CATEGORIA = "outros";
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DIA_MES = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JsonMapper json;
    private final Clock relogio;
    private final ZoneId zona;
    private final Path arquivo;
    /** Pode ser nulo: quem monta à mão sem diário (testes) só não ganha as linhas. */
    private final DiarioDeAcoes diario;

    private final List<Lancamento> lancamentos = new ArrayList<>();

    @Autowired
    public FinancasService(JarvisProperties props, JsonMapper json, DiarioDeAcoes diario) {
        this(json, Clock.systemUTC(), props.zona(), props.pastaDoDiario().resolve(NOME_DO_ARQUIVO),
                diario);
    }

    public FinancasService(JsonMapper json, Clock relogio, ZoneId zona, Path arquivo,
            DiarioDeAcoes diario) {
        this.json = json;
        this.relogio = relogio;
        this.zona = zona;
        this.arquivo = arquivo;
        this.diario = diario;
        carregar();
    }

    // ---------------------------------------------------------------- escrita

    /**
     * Lança um gasto ou um ganho.
     *
     * @param data   {@code AAAA-MM-DD}, ou vazio para hoje. Nem no futuro, nem antes de
     *               {@link #DIAS_NO_PASSADO} dias.
     * @param origem {@code telegram}, {@code hud} ou {@code escritorio}.
     * @throws IllegalArgumentException com a frase que explica o que está errado na entrada
     * @throws IOException              não gravou; nada mudou
     */
    public synchronized Lancamento registrar(Lancamento.Tipo tipo, double valor, String categoria,
            String descricao, String data, String origem) throws IOException {
        if (tipo == null) {
            throw new IllegalArgumentException("o tipo tem de ser gasto ou ganho.");
        }
        long centavos = centavos(valor);
        LocalDate quando = data(data);
        if (lancamentos.size() >= MAX_LANCAMENTOS) {
            throw new IllegalArgumentException("o livro chegou a " + MAX_LANCAMENTOS
                    + " lançamentos; não cabe mais nenhum.");
        }
        Lancamento novo = new Lancamento(novoId(), tipo, centavos, categoria(categoria),
                descricao(descricao), quando, relogio.instant(), origemValida(origem), null);
        lancamentos.add(novo);
        try {
            salvar();
        } catch (IOException | RuntimeException e) {
            lancamentos.remove(lancamentos.size() - 1);
            throw e instanceof IOException io ? io : new IOException("não consegui gravar", e);
        }
        anotarNoDiario(TipoDeAcao.LANCAMENTO_FINANCEIRO, descreverParaODiario(novo), novo.origem());
        return novo;
    }

    /**
     * Tira um lançamento das contas, sem apagá-lo do arquivo.
     *
     * @return o lançamento removido, ou vazio se o id não existe ou já tinha sido removido
     */
    public synchronized Optional<Lancamento> remover(String id, String origem) throws IOException {
        String procurado = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < lancamentos.size(); i++) {
            Lancamento l = lancamentos.get(i);
            if (l.id().equals(procurado) && l.ativo()) {
                Lancamento removido = l.removidoEm(relogio.instant());
                lancamentos.set(i, removido);
                try {
                    salvar();
                } catch (IOException | RuntimeException e) {
                    lancamentos.set(i, l);
                    throw e instanceof IOException io ? io : new IOException("não consegui gravar", e);
                }
                anotarNoDiario(TipoDeAcao.LANCAMENTO_REMOVIDO,
                        "Removi: " + descreverParaODiario(l), origemValida(origem));
                return Optional.of(removido);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- leitura

    public LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), zona);
    }

    /** O resumo do período: totais, as categorias que mais pesam e os últimos lançamentos. */
    public synchronized Resumo resumo(PeriodoFinanceiro periodo) {
        PeriodoFinanceiro qual = periodo == null ? PeriodoFinanceiro.MES_ATUAL : periodo;
        LocalDate hoje = hoje();
        LocalDate de = qual.inicio(hoje);
        LocalDate ate = qual.fim(hoje);
        List<Lancamento> doPeriodo = lancamentos.stream()
                .filter(Lancamento::ativo)
                .filter(l -> !l.data().isBefore(de) && !l.data().isAfter(ate))
                .toList();
        long ganhos = 0;
        long gastos = 0;
        Map<String, Long> gastosPorCategoria = new HashMap<>();
        Map<String, Long> ganhosPorCategoria = new HashMap<>();
        for (Lancamento l : doPeriodo) {
            if (l.tipo() == Lancamento.Tipo.GANHO) {
                ganhos += l.centavos();
                ganhosPorCategoria.merge(l.categoria(), l.centavos(), Long::sum);
            } else {
                gastos += l.centavos();
                gastosPorCategoria.merge(l.categoria(), l.centavos(), Long::sum);
            }
        }
        List<Lancamento> ultimos = doPeriodo.stream()
                .sorted(Comparator.comparing(Lancamento::data)
                        .thenComparing(Lancamento::criadoEm).reversed())
                .limit(ULTIMOS_NO_RESUMO)
                .toList();
        LocalDate primeiro = doPeriodo.stream().map(Lancamento::data).min(Comparator.naturalOrder())
                .orElse(hoje);
        return new Resumo(qual, qual == PeriodoFinanceiro.TUDO ? primeiro : de, ate, ganhos, gastos,
                ordenado(gastosPorCategoria), ordenado(ganhosPorCategoria), ultimos,
                doPeriodo.size());
    }

    /**
     * O resumo de um período. Os números são em centavos; o {@link #texto()} é a forma que vai ao
     * modelo e ao agente, montada aqui em Java para nenhum dos dois somar nada de cabeça.
     */
    public record Resumo(PeriodoFinanceiro periodo, LocalDate de, LocalDate ate, long ganhos,
            long gastos, Map<String, Long> gastosPorCategoria, Map<String, Long> ganhosPorCategoria,
            List<Lancamento> ultimos, int quantidade) {

        public long saldo() {
            return ganhos - gastos;
        }

        public String texto() {
            String intervalo = "(" + de.format(DATA) + " a " + ate.format(DATA) + ")";
            if (quantidade == 0) {
                return "Resumo " + periodo.falado() + " " + intervalo + ": nenhum lançamento.";
            }
            StringBuilder texto = new StringBuilder();
            texto.append("Resumo ").append(periodo.falado()).append(' ').append(intervalo)
                    .append(": ganhos ").append(reais(ganhos))
                    .append(", gastos ").append(reais(gastos))
                    .append(", saldo ").append(reais(saldo()))
                    .append(" (").append(quantidade)
                    .append(quantidade == 1 ? " lançamento)." : " lançamentos).");
            acrescentarCategorias(texto, "Gastos por categoria", gastosPorCategoria);
            acrescentarCategorias(texto, "Ganhos por categoria", ganhosPorCategoria);
            texto.append("\nÚltimos lançamentos:");
            for (Lancamento l : ultimos) {
                texto.append("\n[").append(l.id()).append("] ").append(l.data().format(DIA_MES))
                        .append(' ').append(l.tipo().chave()).append(' ')
                        .append(reais(l.centavos())).append(' ').append(l.categoria());
                if (!l.descricao().isEmpty()) {
                    texto.append(" (").append(l.descricao()).append(')');
                }
            }
            return texto.toString();
        }

        private static void acrescentarCategorias(StringBuilder texto, String titulo,
                Map<String, Long> porCategoria) {
            if (porCategoria.isEmpty()) {
                return;
            }
            texto.append('\n').append(titulo).append(": ");
            int escritas = 0;
            long resto = 0;
            for (Map.Entry<String, Long> e : porCategoria.entrySet()) {
                if (escritas < MAX_CATEGORIAS_NO_RESUMO) {
                    if (escritas > 0) texto.append("; ");
                    texto.append(e.getKey()).append(' ').append(reais(e.getValue()));
                    escritas++;
                } else {
                    resto += e.getValue();
                }
            }
            if (resto > 0) {
                texto.append("; outras categorias ").append(reais(resto));
            }
            texto.append('.');
        }
    }

    /** "R$ 1.234,56", "-R$ 10,00". Escrito à mão: o símbolo do JDK vem com espaço inseparável. */
    public static String reais(long centavos) {
        long absoluto = Math.abs(centavos);
        String numero = String.format(PT_BR, "%,d,%02d", absoluto / 100, absoluto % 100);
        return (centavos < 0 ? "-R$ " : "R$ ") + numero;
    }

    /** Uma linha para confirmar o lançamento a quem pediu. */
    public static String confirmacao(Lancamento l) {
        String texto = (l.tipo() == Lancamento.Tipo.GASTO ? "Gasto" : "Ganho") + " de "
                + reais(l.centavos()) + " em " + l.categoria();
        if (!l.descricao().isEmpty()) {
            texto += " (" + l.descricao() + ")";
        }
        return texto + ", " + l.data().format(DATA) + ". Id " + l.id() + ".";
    }

    // ---------------------------------------------------------------- validação

    static long centavos(double valor) {
        if (Double.isNaN(valor) || Double.isInfinite(valor) || valor <= 0) {
            throw new IllegalArgumentException("o valor tem de ser maior que zero.");
        }
        long centavos = Math.round(valor * 100);
        if (centavos <= 0) {
            throw new IllegalArgumentException("o valor tem de ser de pelo menos R$ 0,01.");
        }
        if (centavos > MAX_CENTAVOS) {
            throw new IllegalArgumentException("o valor passa de " + reais(MAX_CENTAVOS)
                    + "; confira se não sobrou um zero.");
        }
        return centavos;
    }

    private LocalDate data(String data) {
        LocalDate hoje = hoje();
        if (data == null || data.isBlank()) {
            return hoje;
        }
        LocalDate quando;
        try {
            quando = LocalDate.parse(data.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("a data tem de vir como AAAA-MM-DD, ou vazia para hoje.");
        }
        if (quando.isAfter(hoje)) {
            throw new IllegalArgumentException("a data " + quando.format(DATA)
                    + " está no futuro; lance no dia em que acontecer.");
        }
        if (quando.isBefore(hoje.minusDays(DIAS_NO_PASSADO))) {
            throw new IllegalArgumentException("a data " + quando.format(DATA)
                    + " é antiga demais; confira o ano.");
        }
        return quando;
    }

    /** Minúscula, só letras, números, espaço e hífen, até {@link #MAX_CATEGORIA}. */
    static String categoria(String categoria) {
        String limpa = categoria == null ? "" : categoria.toLowerCase(PT_BR)
                .replaceAll("[^\\p{L}\\p{N} -]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (limpa.length() > MAX_CATEGORIA) {
            limpa = limpa.substring(0, MAX_CATEGORIA).trim();
        }
        return limpa.isEmpty() ? SEM_CATEGORIA : limpa;
    }

    /**
     * Sem caractere de controle nem os sinais {@code <<<}/{@code >>>}, até {@link #MAX_DESCRICAO}.
     * A descrição pode ter sido escrita por um agente que leu a internet, e volta ao modelo do
     * Jarvis no resumo: é dado, e não pode fechar caixa nenhuma.
     */
    static String descricao(String descricao) {
        String limpa = descricao == null ? "" : descricao
                .replaceAll("\\p{Cntrl}", " ")
                .replace("<<<", " ").replace(">>>", " ")
                .replace("[", "(").replace("]", ")")
                .replaceAll("\\s+", " ")
                .trim();
        return limpa.length() > MAX_DESCRICAO ? limpa.substring(0, MAX_DESCRICAO - 1).trim() + "…"
                : limpa;
    }

    private static String origemValida(String origem) {
        OrigemDaAcao achada = OrigemDaAcao.porChave(origem);
        return achada == null ? OrigemDaAcao.DESCONHECIDA.chave() : achada.chave();
    }

    private String novoId() {
        while (true) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            boolean usado = lancamentos.stream().anyMatch(l -> l.id().equals(id));
            if (!usado) {
                return id;
            }
        }
    }

    private static Map<String, Long> ordenado(Map<String, Long> porCategoria) {
        Map<String, Long> ordenado = new LinkedHashMap<>();
        porCategoria.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()
                        .thenComparing(Map.Entry.<String, Long>comparingByKey()))
                .forEach(e -> ordenado.put(e.getKey(), e.getValue()));
        return ordenado;
    }

    private static String descreverParaODiario(Lancamento l) {
        return (l.tipo() == Lancamento.Tipo.GASTO ? "Gasto" : "Ganho") + " de "
                + reais(l.centavos()) + " em " + l.categoria() + " [" + l.id() + "]";
    }

    private void anotarNoDiario(TipoDeAcao tipo, String descricao, String origem) {
        if (diario == null) {
            return;
        }
        try {
            OrigemDaAcao quem = OrigemDaAcao.porChave(origem);
            diario.registrar(tipo, descricao, quem == null ? OrigemDaAcao.DESCONHECIDA : quem);
        } catch (RuntimeException e) {
            // O diário é a testemunha, não o guarda: o lançamento já está no disco.
            log.warn("Finanças: não consegui anotar no diário ({})", e.getMessage());
        }
    }

    // ---------------------------------------------------------------- disco

    private void salvar() throws IOException {
        if (arquivo == null) {
            return;
        }
        ObjectNode raiz = json.createObjectNode().put("versao", 1);
        ArrayNode lista = raiz.putArray("lancamentos");
        for (Lancamento l : lancamentos) {
            ObjectNode no = lista.addObject()
                    .put("id", l.id())
                    .put("tipo", l.tipo().chave())
                    .put("centavos", l.centavos())
                    .put("categoria", l.categoria())
                    .put("descricao", l.descricao())
                    .put("data", l.data().toString())
                    .put("criadoEm", l.criadoEm().toString())
                    .put("origem", l.origem());
            if (l.removidoEm() != null) {
                no.put("removidoEm", l.removidoEm().toString());
            }
        }
        ArquivoAtomico.gravar(arquivo, json.writeValueAsString(raiz));
    }

    private void carregar() {
        if (arquivo == null) {
            return;
        }
        for (Path candidato : List.of(arquivo, ArquivoAtomico.backupDe(arquivo))) {
            if (!Files.exists(candidato)) {
                continue;
            }
            try {
                ler(json.readTree(ArquivoAtomico.ler(candidato, MAX_BYTES)));
                return;
            } catch (IOException | RuntimeException e) {
                lancamentos.clear();
                log.warn("Finanças ilegíveis em {}; tentando a cópia ({})", candidato, e.getMessage());
            }
        }
    }

    /** Linha torta (tipo desconhecido, valor fora da faixa, data ilegível) é descartada, não fatal. */
    private void ler(JsonNode raiz) {
        int descartadas = 0;
        for (JsonNode no : raiz.path("lancamentos")) {
            try {
                Lancamento.Tipo tipo = Lancamento.Tipo.porChave(Json.texto(no.path("tipo")));
                long centavos = Json.numero(no.path("centavos"));
                String id = Json.texto(no.path("id"));
                if (tipo == null || id.isBlank() || centavos <= 0 || centavos > MAX_CENTAVOS) {
                    descartadas++;
                    continue;
                }
                String removido = Json.texto(no.path("removidoEm"));
                lancamentos.add(new Lancamento(id, tipo, centavos,
                        categoria(Json.texto(no.path("categoria"))),
                        descricao(Json.texto(no.path("descricao"))),
                        LocalDate.parse(Json.texto(no.path("data"))),
                        Instant.parse(Json.texto(no.path("criadoEm"))),
                        origemValida(Json.texto(no.path("origem"))),
                        removido.isBlank() ? null : Instant.parse(removido)));
            } catch (RuntimeException e) {
                descartadas++;
            }
        }
        if (descartadas > 0) {
            log.warn("Finanças: {} lançamento(s) ilegível(is) descartado(s) de {}", descartadas,
                    arquivo);
        }
    }
}
