package dev.jarvis.ferramentas;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.ClimaService.PrevisaoDia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.pc.PcService;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.MontadorDeCardDeDados;
import dev.jarvis.tela.MontadorDeCardDePesquisa;
import dev.jarvis.tela.PresencaDaHud;
import dev.jarvis.util.Json;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Abre um gráfico na HUD com números que o modelo não escreveu.
 *
 * É a outra metade do {@code mostrar_na_tela}: lá o modelo digita os valores de uma pesquisa e o
 * card sai marcado como {@code origem: "pesquisa"}; aqui ele escolhe só <b>qual</b> gráfico quer, e
 * quem preenche eixo, séries e unidade é o Java, com {@code origem: "servidor"}.
 *
 * A divisão existe porque gráfico é a forma mais convincente de errar: um valor trocado numa série
 * de sete pontos não parece engano, parece tendência. Por isso o schema desta ferramenta
 * <b>não tem</b> os campos {@code eixoX}, {@code series} nem {@code origem} — o modelo não tem onde
 * escrever um número, e não é por educação que ele não escreve.
 *
 * Fonte nova entra em dois lugares: uma entrada em {@link #FONTES} e um {@code case} no
 * {@code executarDetalhado}. Hoje são três: {@code clima_7_dias}, {@code planilha} e
 * {@code custo_30_dias}.
 */
@Component
public class GraficoDeDadosFerramenta extends FerramentaDeTela {

    private static final Locale BR = Locale.of("pt", "BR");
    private static final DateTimeFormatter QUANDO = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    /** As fontes de número que o Jarvis mede ou lê sozinho. */
    static final String CLIMA_7_DIAS = "clima_7_dias";
    static final String PLANILHA = "planilha";
    static final String CUSTO_30_DIAS = "custo_30_dias";
    static final List<String> FONTES = List.of(CLIMA_7_DIAS, PLANILHA, CUSTO_30_DIAS);

    /** Dias do gráfico de clima: a semana à frente, hoje inclusive. */
    static final int DIAS_DO_CLIMA = 7;
    /** Teto de colunas de valores, que é o teto de séries do gráfico. */
    static final int MAX_COLUNAS = MontadorDeCardDePesquisa.MAX_SERIES;
    /** O agente recusa leitura de 100 linhas ou mais numa chamada. */
    static final int MAX_LINHAS_LIDAS = 99;
    /** Dias do gráfico de custo: o mês para trás, hoje inclusive. */
    static final int DIAS_DO_CUSTO = 30;
    /** Cinco dias por barra: trinta pontos não cabem nas doze categorias da empilhada. */
    static final int DIAS_POR_BLOCO = 5;
    /**
     * O que a planilha desenha.
     *
     * Rosca e barra empilhada pedem dado de composição — fatias de um todo —, e uma coluna
     * qualquer de um XLSX não é isso. Deixar o enum aberto aqui aceitaria calado um desenho que
     * este caminho não valida.
     */
    static final List<String> TIPOS_DA_PLANILHA = List.of("linha", "barras");

    private static final DateTimeFormatter DIA = DateTimeFormatter.ofPattern("dd");
    private static final DateTimeFormatter DIA_E_MES = DateTimeFormatter.ofPattern("dd/MM");

    private final ClimaService clima;
    private final PcService pc;
    private final MedidorDeCusto medidor;
    private final JarvisProperties props;

    public GraficoDeDadosFerramenta(PresencaDaHud hud, JsonMapper json, JarvisProperties props,
            ClimaService clima, PcService pc, MedidorDeCusto medidor) {
        super(hud, json);
        this.props = props;
        this.clima = clima;
        this.pc = pc;
        this.medidor = medidor;
    }

    @Override
    public String nome() {
        return "grafico_de_dados";
    }
    @Override public GrupoFerramenta grupo() { return GrupoFerramenta.TELA; }
    @Override public boolean simples() { return true; }

    @Override
    public String descricao() {
        return "Mostra gráfico na HUD com números preenchidos pelo servidor. Fontes: clima_7_dias "
                + "(previsão semanal), planilha (XLSX/CSV pelo ID de listar_arquivos) e "
                + "custo_30_dias (gasto mensal). Escolha fonte e título com a conclusão; não escreva "
                + "eixos nem valores. Para números pesquisados na web, use mostrar_na_tela com "
                + "layout grafico. Sem 'Na tela agora' no contexto, não há HUD aberta.";
    }

    @Override
    public ObjectNode esquemaEntrada() {
        ObjectNode esquema = json.createObjectNode();
        esquema.put("type", "object");
        ObjectNode campos = esquema.putObject("properties");

        ObjectNode fonte = campos.putObject("fonte");
        fonte.put("type", "string");
        fonte.put("description", "De onde o servidor tira os números. clima_7_dias e custo_30_dias "
                + "não precisam de mais nada; planilha precisa do arquivo e das colunas.");
        FONTES.forEach(fonte.putArray("enum")::add);

        campos.putObject("titulo").put("type", "string")
                .put("description", "A CONCLUSÃO em uma frase, que nomeia a janela: 'A semana "
                        + "esquenta a partir de quarta'. Não repita os números: eles vão na tela.");
        campos.putObject("resumo").put("type", "string")
                .put("description", "O mesmo texto que você vai falar em voz alta sobre isto.");
        campos.putObject("categoria").put("type", "string")
                .put("description", "Opcional: o assunto em uma palavra. O servidor preenche um "
                        + "padrão quando você não manda.");
        campos.putObject("tema").put("type", "string")
                .put("description", "Opcional: o subtítulo do card.");

        ObjectNode tipo = campos.putObject("tipo");
        tipo.put("type", "string");
        tipo.put("description", "Só na fonte planilha: linha para o que anda no tempo ("
                + MontadorDeCardDePesquisa.MAX_PONTOS_LINHA + " pontos no máximo), barras para "
                + "comparar categorias (até " + MontadorDeCardDePesquisa.MAX_BARRAS + "). "
                + "No clima e no custo o servidor escolhe.");
        TIPOS_DA_PLANILHA.forEach(tipo.putArray("enum")::add);

        campos.putObject("arquivoId").put("type", "string")
                .put("description", "Fonte planilha: o id do XLSX ou CSV, vindo de listar_arquivos.");
        campos.putObject("aba").put("type", "string")
                .put("description", "Fonte planilha, XLSX: o nome da aba. Chame ler_arquivo sem aba "
                        + "para saber quais existem.");
        campos.putObject("colunaRotulos").put("type", "string")
                .put("description", "A coluna dos rótulos do eixo, pela letra: 'A'. No CSV, a "
                        + "primeira coluna é 'A'.");

        ObjectNode colunas = campos.putObject("colunasValores");
        colunas.put("type", "array");
        colunas.put("maxItems", MAX_COLUNAS);
        colunas.put("description", "As colunas de números, pela letra, até " + MAX_COLUNAS
                + " — uma série cada. Toda célula delas tem de ser número.");
        colunas.putObject("items").put("type", "string");

        campos.putObject("linhaInicial").put("type", "integer")
                .put("description", "A primeira linha de dados (1 é a primeira do arquivo).");
        campos.putObject("linhaFinal").put("type", "integer")
                .put("description", "A última linha de dados. Uma linha vira um ponto do gráfico.");
        campos.putObject("linhaCabecalho").put("type", "integer")
                .put("description", "Opcional: a linha de onde o servidor lê o nome de cada série.");
        campos.putObject("unidade").put("type", "string")
                .put("description", "Só na fonte planilha: o símbolo dos valores, copiado do "
                        + "cabeçalho da planilha — 'R$', '%', 'kg'. Até "
                        + MontadorDeCardDePesquisa.MAX_UNIDADE + " caracteres. No clima o servidor "
                        + "sabe a unidade e ignora este campo.");

        esquema.putArray("required").add("fonte").add("titulo").add("resumo");
        return esquema;
    }

    @Override
    public ResultadoFerramenta executarDetalhado(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        // Antes de tudo: nem a Open-Meteo nem os 55 s de fila do PC valem a pena para ninguém ver.
        if (telaFechada()) {
            return ResultadoFerramenta.texto(SEM_TELA);
        }
        if (entrada == null || !entrada.isObject()) {
            throw new IllegalArgumentException("mande um objeto com fonte, titulo e resumo");
        }
        String fonte = Json.texto(entrada.path("fonte")).trim();
        return switch (fonte) {
            case CLIMA_7_DIAS -> doClima(entrada, progresso);
            case PLANILHA -> daPlanilha(entrada, progresso);
            case CUSTO_30_DIAS -> doCusto(entrada, progresso);
            default -> throw new IllegalArgumentException(
                    "fonte deve ser uma de " + FONTES + ", não '" + fonte + "'");
        };
    }

    // ---------------------------------------------------------------- clima

    /**
     * Os dois cards da semana: temperatura em linha e chuva em barras.
     *
     * São dois porque um {@code Grafico} tem uma unidade só, e 30% na mesma escala de 22 °C é
     * exatamente o gráfico que mente. Deixar a chuva de fora, por outro lado, tiraria da tela o
     * número que decide levar guarda-chuva.
     */
    private ResultadoFerramenta doClima(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        List<PrevisaoDia> dias = clima.previsaoDias(DIAS_DO_CLIMA);
        List<PrevisaoDia> completos = new ArrayList<>();
        // A série para no primeiro dia sem temperatura: linha com buraco não é representável.
        for (PrevisaoDia dia : dias) {
            if (!dia.completa()) {
                break;
            }
            completos.add(dia);
        }
        if (completos.size() < MontadorDeCardDePesquisa.MIN_PONTOS_LINHA) {
            return ResultadoFerramenta.texto("A previsão não veio com temperatura suficiente para "
                    + "um gráfico (" + completos.size() + " dia(s) completo(s)). Responda sem "
                    + "prometer card.");
        }

        List<String> eixoX = new ArrayList<>(completos.size());
        List<Double> maximas = new ArrayList<>(completos.size());
        List<Double> minimas = new ArrayList<>(completos.size());
        for (PrevisaoDia dia : completos) {
            eixoX.add(diaDaSemana(dia.data()));
            maximas.add(dia.maxima());
            minimas.add(dia.minima());
        }

        String cidade = completos.getFirst().cidade();
        String consultadoEm = agora();
        String origem = origem(progresso);
        String titulo = Json.texto(entrada.path("titulo")).trim();
        String resumo = Json.texto(entrada.path("resumo")).trim();
        String categoria = ou(entrada, "categoria", "clima");
        String tema = ou(entrada, "tema", "previsão de " + completos.size() + " dias em " + cidade);

        List<CardTela> cards = new ArrayList<>(2);
        cards.add(MontadorDeCardDeDados.montar(titulo, categoria, tema, resumo, "linha", eixoX,
                List.of(new DadosDoCard.Grafico.Serie("máxima", maximas),
                        new DadosDoCard.Grafico.Serie("mínima", minimas)),
                "°C", destaqueNoMaior(maximas), origem, consultadoEm));

        // A chuva só vira card quando todos os dias trouxeram a chance: barra faltando no meio de
        // uma comparação é pior que comparação nenhuma.
        List<Double> chuvas = new ArrayList<>(completos.size());
        for (PrevisaoDia dia : completos) {
            if (dia.chanceChuvaPct() == null) {
                chuvas = null;
                break;
            }
            chuvas.add((double) dia.chanceChuvaPct());
        }
        if (chuvas != null && eixoX.size() <= MontadorDeCardDePesquisa.MAX_BARRAS) {
            cards.add(MontadorDeCardDeDados.montar("Chance de chuva por dia", categoria,
                    "chance de chuva em " + cidade, resumo, "barras", eixoX,
                    List.of(new DadosDoCard.Grafico.Serie("chance de chuva", chuvas)),
                    "%", destaqueNoMaior(chuvas), origem, consultadoEm));
        }

        String aviso = chuvas == null ? " A chance de chuva não veio e ficou fora." : "";
        return new ResultadoFerramenta(
                cards.size() + (cards.size() == 1 ? " card na tela (id " : " cards na tela (ids ")
                        + ids(cards) + "). " + resumoDoClima(completos) + aviso
                        + " Diga só uma frase curta; os números já estão à vista.",
                List.of(), List.of(), null, cards, List.of());
    }

    /** "seg", "ter"... O rótulo do eixo é curto porque na tela ele fica embaixo de cada ponto. */
    private static String diaDaSemana(LocalDate data) {
        String dia = data.getDayOfWeek().getDisplayName(TextStyle.SHORT, BR);
        String curto = dia.length() > 3 ? dia.substring(0, 3) : dia;
        return curto.replace(".", "").toLowerCase(BR);
    }

    /**
     * Uma frase com os extremos, escrita em Java.
     *
     * O modelo precisa de algo para falar, e é melhor que esse algo venha daqui: assim nem a fala
     * depende de ele ter lido certo um número que ele nunca viu.
     */
    private static String resumoDoClima(List<PrevisaoDia> dias) {
        double maior = dias.getFirst().maxima();
        double menor = dias.getFirst().minima();
        for (PrevisaoDia dia : dias) {
            maior = Math.max(maior, dia.maxima());
            menor = Math.min(menor, dia.minima());
        }
        return String.format(BR, "Máxima de %.0f °C e mínima de %.0f °C na semana.", maior, menor);
    }

    private static DadosDoCard.Grafico.Destaque destaqueNoMaior(List<Double> valores) {
        int indice = 0;
        for (int i = 1; i < valores.size(); i++) {
            if (valores.get(i) > valores.get(indice)) {
                indice = i;
            }
        }
        return new DadosDoCard.Grafico.Destaque(0, indice);
    }

    // ---------------------------------------------------------------- custo

    /**
     * O gasto do mês em barras empilhadas por origem.
     *
     * Trinta pontos não cabem nas doze categorias da barra empilhada, então os dias são somados em
     * blocos de cinco — seis barras, com o mês inteiro à vista. Bloco sem nenhum dia registrado sai
     * do eixo pela mesma razão que o {@code serieDiaria} omite o dia: zero ali desenharia "não
     * gastei nada" onde a verdade é "eu estava desligado".
     *
     * O modelo não escolhe o tipo nem a divisão: o total do mês é a pergunta errada ("gastei US$
     * 1,20" não diz o que mexer), e é a composição por origem que responde.
     */
    private ResultadoFerramenta doCusto(JsonNode entrada, OuvinteProgresso progresso) {
        LocalDate hoje = LocalDate.now(props.zona());
        LocalDate inicio = hoje.minusDays(DIAS_DO_CUSTO - 1L);
        int quantosBlocos = DIAS_DO_CUSTO / DIAS_POR_BLOCO;

        List<Map<Origem, Double>> baldes = new ArrayList<>(quantosBlocos);
        for (int b = 0; b < quantosBlocos; b++) {
            baldes.add(new EnumMap<>(Origem.class));
        }
        for (MedidorDeCusto.DiaDeCusto dia : medidor.serieDiaria(DIAS_DO_CUSTO)) {
            long desdeOInicio = ChronoUnit.DAYS.between(inicio, dia.dia());
            if (desdeOInicio < 0 || desdeOInicio >= DIAS_DO_CUSTO) {
                continue;
            }
            Map<Origem, Double> balde = baldes.get((int) (desdeOInicio / DIAS_POR_BLOCO));
            dia.porOrigem().forEach((origem, custo) -> balde.merge(origem, custo, Double::sum));
        }

        List<String> eixoX = new ArrayList<>(quantosBlocos);
        List<Map<Origem, Double>> comGasto = new ArrayList<>(quantosBlocos);
        Map<Origem, Double> doPeriodo = new EnumMap<>(Origem.class);
        for (int b = 0; b < quantosBlocos; b++) {
            Map<Origem, Double> balde = baldes.get(b);
            if (balde.isEmpty()) {
                continue;
            }
            LocalDate comeco = inicio.plusDays((long) b * DIAS_POR_BLOCO);
            eixoX.add(comeco.format(DIA) + "-"
                    + comeco.plusDays(DIAS_POR_BLOCO - 1L).format(DIA_E_MES));
            comGasto.add(balde);
            balde.forEach((origem, custo) -> doPeriodo.merge(origem, custo, Double::sum));
        }
        if (eixoX.isEmpty()) {
            return ResultadoFerramenta.texto("Não há gasto registrado nos últimos "
                    + DIAS_DO_CUSTO + " dias. Responda sem prometer card.");
        }

        List<Origem> maiores = doPeriodo.entrySet().stream()
                .sorted(Map.Entry.<Origem, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        CardTela card = MontadorDeCardDeDados.montar(
                Json.texto(entrada.path("titulo")).trim(),
                ou(entrada, "categoria", "custo"),
                ou(entrada, "tema", "últimos " + DIAS_DO_CUSTO + " dias, por origem"),
                Json.texto(entrada.path("resumo")).trim(),
                MontadorDeCardDePesquisa.BARRAS_EMPILHADAS, eixoX, partes(comGasto, maiores),
                "US$", null, origem(progresso), agora());
        return ResultadoFerramenta.comCard("Card na tela (id " + card.id() + "). "
                + resumoDoCusto(doPeriodo, maiores.getFirst())
                + " Diga só uma frase curta; os números já estão à vista.", card);
    }

    /**
     * Uma parte da barra por origem, com as menores somadas em "outras" quando passam de quatro.
     *
     * Quem corta é o Java: deixar o modelo escolher qual origem desaparece seria deixá-lo escolher
     * o que o dono não vê.
     */
    private static List<DadosDoCard.Grafico.Serie> partes(List<Map<Origem, Double>> blocos,
            List<Origem> maiores) {
        int teto = MontadorDeCardDePesquisa.MAX_PARTES_EMPILHADAS;
        boolean juntarOResto = maiores.size() > teto;
        List<Origem> proprias = juntarOResto ? maiores.subList(0, teto - 1) : maiores;

        List<DadosDoCard.Grafico.Serie> series = new ArrayList<>(proprias.size() + 1);
        for (Origem origem : proprias) {
            List<Double> valores = new ArrayList<>(blocos.size());
            for (Map<Origem, Double> balde : blocos) {
                valores.add(arredondado(balde.getOrDefault(origem, 0.0)));
            }
            series.add(new DadosDoCard.Grafico.Serie(origem.chave(), valores));
        }
        if (juntarOResto) {
            List<Double> outras = new ArrayList<>(blocos.size());
            for (Map<Origem, Double> balde : blocos) {
                double soma = 0;
                for (Map.Entry<Origem, Double> item : balde.entrySet()) {
                    if (!proprias.contains(item.getKey())) {
                        soma += item.getValue();
                    }
                }
                outras.add(arredondado(soma));
            }
            series.add(new DadosDoCard.Grafico.Serie("outras", outras));
        }
        return series;
    }

    /** Quatro casas: o custo é da ordem de centavos, e o lixo de ponto flutuante viraria rótulo. */
    private static double arredondado(double valor) {
        return Math.round(valor * 10_000d) / 10_000d;
    }

    /** A frase do custo, escrita em Java pelo mesmo motivo do {@link #resumoDoClima}. */
    private static String resumoDoCusto(Map<Origem, Double> doPeriodo, Origem maior) {
        double total = 0;
        for (double custo : doPeriodo.values()) {
            total += custo;
        }
        return String.format(BR, "US$ %.2f nos últimos %d dias, a maior parte em %s (US$ %.2f).",
                total, DIAS_DO_CUSTO, maior.chave(), doPeriodo.get(maior));
    }

    // ------------------------------------------------------------- planilha

    /**
     * Um card com colunas de uma planilha do PC, lidas pelo mesmo {@code ler_arquivo} do agente.
     *
     * O modelo diz qual arquivo, qual aba e quais colunas; o valor de cada célula vem do agente e
     * é convertido aqui. Célula que não é número derruba o gráfico inteiro, nomeando o endereço:
     * um texto virando zero seria uma barra zerada que o dono leria como "vendeu nada".
     */
    private ResultadoFerramenta daPlanilha(JsonNode entrada, OuvinteProgresso progresso)
            throws Exception {
        String arquivoId = Json.texto(entrada.path("arquivoId")).trim();
        if (arquivoId.isEmpty()) {
            throw new IllegalArgumentException(
                    "falta o 'arquivoId'; pegue o id em listar_arquivos");
        }
        String tipo = Json.texto(entrada.path("tipo")).trim();
        if (!TIPOS_DA_PLANILHA.contains(tipo)) {
            throw new IllegalArgumentException("mande 'tipo' linha ou barras, não '" + tipo + "'");
        }
        int colunaRotulos = coluna(Json.texto(entrada.path("colunaRotulos")).trim(), "colunaRotulos");
        List<Integer> colunasValores = new ArrayList<>();
        List<String> letrasValores = new ArrayList<>();
        JsonNode lista = entrada.path("colunasValores");
        if (!lista.isArray() || lista.isEmpty()) {
            throw new IllegalArgumentException(
                    "mande de 1 a " + MAX_COLUNAS + " letras em 'colunasValores'");
        }
        if (lista.size() > MAX_COLUNAS) {
            throw new IllegalArgumentException(
                    "no máximo " + MAX_COLUNAS + " colunas de valores; você mandou " + lista.size());
        }
        for (JsonNode letra : lista.values()) {
            String texto = Json.texto(letra).trim();
            letrasValores.add(texto.toUpperCase(Locale.ROOT));
            colunasValores.add(coluna(texto, "colunasValores"));
        }

        int linhaInicial = (int) Json.numero(entrada.path("linhaInicial"));
        int linhaFinal = (int) Json.numero(entrada.path("linhaFinal"));
        if (linhaInicial < 1 || linhaFinal < linhaInicial) {
            throw new IllegalArgumentException(
                    "linhaInicial e linhaFinal têm de ser linhas válidas, com a final não menor que a inicial");
        }
        int pontos = linhaFinal - linhaInicial + 1;
        MontadorDeCardDePesquisa.LimiteDoEixo limite = MontadorDeCardDePesquisa.limiteDoEixo(tipo);
        if (pontos > limite.maximo()) {
            throw new IllegalArgumentException("gráfico de " + tipo + " cabe " + limite.maximo()
                    + " " + limite.substantivo() + "; o intervalo pedido tem " + pontos + " linhas");
        }
        int linhaCabecalho = (int) Json.numero(entrada.path("linhaCabecalho"));

        // A leitura cobre o cabeçalho junto quando ele está fora do intervalo de dados: é uma ida
        // ao PC em vez de duas, e o agente devolve as duas faixas na mesma resposta.
        int primeira = linhaCabecalho > 0 ? Math.min(linhaCabecalho, linhaInicial) : linhaInicial;
        int ultima = Math.max(linhaFinal, linhaCabecalho);
        if (ultima - primeira + 1 > MAX_LINHAS_LIDAS) {
            throw new IllegalArgumentException("a linha de cabeçalho está longe demais dos dados; "
                    + "o agente lê no máximo " + MAX_LINHAS_LIDAS + " linhas por chamada");
        }
        int colunaInicial = colunaRotulos;
        int colunaFinal = colunaRotulos;
        for (int valores : colunasValores) {
            colunaInicial = Math.min(colunaInicial, valores);
            colunaFinal = Math.max(colunaFinal, valores);
        }

        ObjectNode pedido = json.createObjectNode();
        pedido.put("id", arquivoId);
        String aba = Json.texto(entrada.path("aba")).trim();
        if (!aba.isEmpty()) {
            pedido.put("aba", aba);
        }
        pedido.put("linhaInicial", primeira);
        pedido.put("linhaFinal", ultima);
        pedido.put("colunaInicial", colunaInicial);
        pedido.put("colunaFinal", colunaFinal);
        pedido.put("quantidadeLinhas", ultima - primeira + 1);

        ConsultaArquivosPc.Resposta resposta = lerPlanilha(pedido, progresso);
        if (!resposta.temDados()) {
            return ResultadoFerramenta.texto(resposta.recado());
        }
        JsonNode dados = resposta.dados();

        Map<String, JsonNode> celulas = porEndereco(dados);
        List<String> eixoX = new ArrayList<>(pontos);
        for (int linha = linhaInicial; linha <= linhaFinal; linha++) {
            String rotulo = rotulo(celulas.get(letra(colunaRotulos) + linha));
            if (rotulo.isBlank()) {
                throw new IllegalArgumentException("célula " + letra(colunaRotulos) + linha
                        + " está vazia; o eixo precisa de um rótulo em cada linha");
            }
            eixoX.add(corte(rotulo, MontadorDeCardDePesquisa.MAX_ROTULO_EIXO));
        }

        List<DadosDoCard.Grafico.Serie> series = new ArrayList<>(colunasValores.size());
        for (int c = 0; c < colunasValores.size(); c++) {
            String letra = letra(colunasValores.get(c));
            List<Double> valores = new ArrayList<>(pontos);
            for (int linha = linhaInicial; linha <= linhaFinal; linha++) {
                valores.add(numeroDaCelula(celulas.get(letra + linha), letra + linha));
            }
            series.add(new DadosDoCard.Grafico.Serie(
                    nomeDaSerie(celulas, letra, linhaCabecalho, entrada, c, letrasValores), valores));
        }

        CardTela card = MontadorDeCardDeDados.montar(
                Json.texto(entrada.path("titulo")).trim(),
                ou(entrada, "categoria", "planilha"),
                ou(entrada, "tema", nomeDoArquivo(dados)),
                Json.texto(entrada.path("resumo")).trim(),
                tipo, eixoX, series, Json.texto(entrada.path("unidade")).trim(), null,
                origem(progresso), agora());
        return ResultadoFerramenta.comCard("Card na tela (id " + card.id() + "), com "
                + series.size() + " série(s) de " + pontos + " valores lidos da planilha. "
                + "Diga só uma frase curta; os números já estão à vista.", card);
    }

    /**
     * A ida ao PC, separada para o teste substituir.
     *
     * Esta é a única parte do caminho da planilha que precisa de um agente do outro lado; o resto
     * é conversão de célula em número, que é justamente o que vale testar.
     */
    protected ConsultaArquivosPc.Resposta lerPlanilha(ObjectNode pedido, OuvinteProgresso progresso)
            throws Exception {
        return ConsultaArquivosPc.consultar(pc, "ler_arquivo", pedido, progresso);
    }

    /**
     * As células do agente indexadas pelo endereço ("B7").
     *
     * O XLSX vem em {@code celulas}, com endereço em cada item; o CSV vem em {@code linhas}, como
     * matriz de texto sem endereço — aqui os dois viram o mesmo mapa, para o resto do método não
     * saber de qual formato está lendo.
     */
    private static Map<String, JsonNode> porEndereco(JsonNode dados) {
        Map<String, JsonNode> mapa = new HashMap<>();
        for (JsonNode celula : dados.path("celulas").values()) {
            String endereco = Json.texto(celula.path("endereco")).trim().toUpperCase(Locale.ROOT);
            if (!endereco.isEmpty()) {
                mapa.put(endereco, celula);
            }
        }
        if (!mapa.isEmpty()) {
            return mapa;
        }
        // CSV: a primeira linha devolvida é a 'linhaInicial' que pedimos.
        JsonNode linhas = dados.path("linhas");
        long primeira = Json.numero(dados.path("continuarLinha"));
        long base = primeira > 0 ? primeira - linhas.size() : 1;
        for (int l = 0; l < linhas.size(); l++) {
            JsonNode campos = linhas.get(l);
            for (int c = 0; c < campos.size(); c++) {
                mapa.put(letra(c + 1) + (base + l), campos.get(c));
            }
        }
        return mapa;
    }

    /** O nome da série: do cabeçalho da planilha, do que o modelo chamou, ou da letra da coluna. */
    private static String nomeDaSerie(Map<String, JsonNode> celulas, String letra,
            int linhaCabecalho, JsonNode entrada, int indice, List<String> letras) {
        if (linhaCabecalho > 0) {
            String doArquivo = rotulo(celulas.get(letra + linhaCabecalho));
            if (!doArquivo.isBlank()) {
                return corte(doArquivo, MontadorDeCardDePesquisa.MAX_NOME_SERIE);
            }
        }
        JsonNode nomes = entrada.path("nomesSeries");
        if (nomes.isArray() && indice < nomes.size()) {
            String doModelo = Json.texto(nomes.get(indice)).trim();
            if (!doModelo.isBlank()) {
                return corte(doModelo, MontadorDeCardDePesquisa.MAX_NOME_SERIE);
            }
        }
        return "coluna " + letras.get(indice);
    }

    private static String nomeDoArquivo(JsonNode dados) {
        String nome = Json.texto(dados.path("nome")).trim();
        return nome.isEmpty() ? "planilha" : corte(nome, MontadorDeCardDePesquisa.MAX_CATEGORIA);
    }

    /**
     * O número de uma célula, ou a recusa nomeando o endereço.
     *
     * Texto não vira zero e célula vazia não vira zero: as duas coisas desenhariam um ponto no
     * chão do gráfico, que é uma afirmação — e uma afirmação que ninguém fez.
     */
    private static Double numeroDaCelula(JsonNode celula, String endereco) {
        if (celula == null || celula.isMissingNode() || celula.isNull()) {
            throw new IllegalArgumentException("célula " + endereco
                    + " não veio na leitura; o gráfico precisa de número em toda a coluna de valores");
        }
        JsonNode valor = celula.has("valor") ? celula.path("valor") : celula;
        if (valor.isNumber()) {
            double numero = valor.doubleValue();
            if (!Double.isFinite(numero)) {
                throw new IllegalArgumentException("célula " + endereco + " não é número finito");
            }
            return numero;
        }
        String texto = valor.isString() ? valor.stringValue().trim() : "";
        if (!texto.isEmpty()) {
            try {
                // A planilha pode guardar "12,5" como texto; "R$ 12,50" não, isso é outra coisa.
                return Double.valueOf(texto.replace(".", "").replace(',', '.'));
            } catch (NumberFormatException ignorado) {
                throw new IllegalArgumentException("célula " + endereco + " ('" + corte(texto, 20)
                        + "') não é número; o gráfico precisa de número em toda a coluna de valores");
            }
        }
        throw new IllegalArgumentException("célula " + endereco
                + " está vazia; o gráfico precisa de número em toda a coluna de valores");
    }

    /** O texto de uma célula de rótulo, que pode ter vindo como número ("2026"). */
    private static String rotulo(JsonNode celula) {
        if (celula == null || celula.isMissingNode() || celula.isNull()) {
            return "";
        }
        JsonNode valor = celula.has("valor") ? celula.path("valor") : celula;
        if (valor.isString()) {
            return valor.stringValue().trim();
        }
        if (valor.isNumber()) {
            double numero = valor.doubleValue();
            return numero == Math.floor(numero) && Double.isFinite(numero)
                    ? String.valueOf((long) numero)
                    : String.valueOf(numero);
        }
        return "";
    }

    /** "A" vira 1, "AB" vira 28: a mesma conta das colunas do Excel. */
    static int coluna(String letra, String campo) {
        String limpo = letra == null ? "" : letra.trim().toUpperCase(Locale.ROOT);
        if (limpo.isEmpty() || limpo.length() > 3 || !limpo.chars().allMatch(c -> c >= 'A' && c <= 'Z')) {
            throw new IllegalArgumentException(
                    "'" + campo + "' tem de ser a letra da coluna (A, B, ... AA), não '" + letra + "'");
        }
        int indice = 0;
        for (char c : limpo.toCharArray()) {
            indice = indice * 26 + (c - 'A' + 1);
        }
        return indice;
    }

    static String letra(int coluna) {
        StringBuilder letra = new StringBuilder();
        int resto = coluna;
        while (resto > 0) {
            int digito = (resto - 1) % 26;
            letra.insert(0, (char) ('A' + digito));
            resto = (resto - 1 - digito) / 26;
        }
        return letra.toString();
    }

    private static String corte(String texto, int max) {
        return texto.length() <= max ? texto : texto.substring(0, max);
    }

    private static String ou(JsonNode entrada, String campo, String padrao) {
        String valor = Json.texto(entrada.path(campo)).trim();
        return valor.isEmpty() ? padrao : valor;
    }

    private static String ids(List<CardTela> cards) {
        StringBuilder ids = new StringBuilder();
        for (CardTela card : cards) {
            if (!ids.isEmpty()) {
                ids.append(", ");
            }
            ids.append(card.id());
        }
        return ids.toString();
    }

    private String agora() {
        return ZonedDateTime.now(props.zona()).format(QUANDO);
    }
}
