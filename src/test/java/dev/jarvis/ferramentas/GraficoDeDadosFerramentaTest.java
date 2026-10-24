package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.ClimaService.PrevisaoDia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.custo.Origem;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.DadosDoCard;
import dev.jarvis.tela.PresencaDaHud;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Testa a ferramenta sem rede e sem PC: o clima entra por uma subclasse do {@link ClimaService} e a
 * leitura da planilha pela costura {@code lerPlanilha}.
 *
 * O que estes testes guardam é o contrato que dá nome à ferramenta: nenhum número do card vem da
 * entrada. Por isso o teste do schema afirma a <b>ausência</b> de campos, que é uma coisa estranha
 * de testar até se lembrar de que é ela que impede o modelo de inventar uma série.
 */
class GraficoDeDadosFerramentaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final JarvisProperties PROPS = new JarvisProperties("Anderson",
            "America/Sao_Paulo", "", null, null, null, null, null, null, null, null, null, null);

    private static final OuvinteProgresso DO_TELEGRAM = new OuvinteProgresso() {
        @Override
        public String canal() {
            return "telegram";
        }
    };

    /** Sete dias completos, com a máxima subindo até o quinto. */
    private static List<PrevisaoDia> semana() {
        double[] maximas = { 21.5, 19.8, 23.4, 26.1, 28.0, 27.2, 24.6 };
        double[] minimas = { 12.1, 13.0, 14.2, 15.5, 17.3, 16.8, 15.1 };
        int[] chuvas = { 10, 77, 55, 5, 0, 20, 90 };
        List<PrevisaoDia> dias = new ArrayList<>(7);
        for (int i = 0; i < 7; i++) {
            dias.add(new PrevisaoDia(LocalDate.of(2026, 10, 19).plusDays(i), "Curitiba",
                    minimas[i], maximas[i], chuvas[i], 10.0, "nublado"));
        }
        return dias;
    }

    private static GraficoDeDadosFerramenta com(PresencaDaHud hud, List<PrevisaoDia> dias) {
        ClimaService clima = new ClimaService(null, JSON, PROPS) {
            @Override
            public List<PrevisaoDia> previsaoDias(int quantos) {
                return dias;
            }
        };
        return new GraficoDeDadosFerramenta(hud, JSON, PROPS, clima, null, null);
    }

    /** A ferramenta com a leitura do PC trocada por um JSON fixo, no formato do agente. */
    private static GraficoDeDadosFerramenta comPlanilha(ConsultaArquivosPc.Resposta resposta) {
        ClimaService clima = new ClimaService(null, JSON, PROPS);
        return new GraficoDeDadosFerramenta(() -> 1, JSON, PROPS, clima, null, null) {
            @Override
            protected ConsultaArquivosPc.Resposta lerPlanilha(ObjectNode pedido,
                    OuvinteProgresso progresso) {
                pedidoVisto = pedido;
                return resposta;
            }
        };
    }

    private static ObjectNode pedidoVisto;

    private static ObjectNode entradaDeClima() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("fonte", "clima_7_dias");
        entrada.put("titulo", "A semana esquenta a partir de quarta");
        entrada.put("resumo", "A máxima sobe até quinta e a chuva volta no domingo.");
        return entrada;
    }

    private static ObjectNode entradaDePlanilha() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("fonte", "planilha");
        entrada.put("titulo", "Março foi o melhor mês");
        entrada.put("resumo", "As vendas cresceram até março.");
        entrada.put("arquivoId", "11111111-2222-3333-4444-555555555555");
        entrada.put("aba", "Vendas");
        entrada.put("tipo", "barras");
        entrada.put("colunaRotulos", "A");
        entrada.putArray("colunasValores").add("B");
        entrada.put("linhaInicial", 2);
        entrada.put("linhaFinal", 4);
        entrada.put("linhaCabecalho", 1);
        entrada.put("unidade", "R$");
        return entrada;
    }

    /** O formato do agente: um objeto por célula, com endereço, tipo e valor. */
    private static ConsultaArquivosPc.Resposta celulas(String... enderecoEValor) {
        ObjectNode dados = JSON.createObjectNode();
        dados.put("nome", "vendas-2026.xlsx");
        ArrayNode celulas = dados.putArray("celulas");
        for (int i = 0; i < enderecoEValor.length; i += 2) {
            ObjectNode celula = celulas.addObject();
            celula.put("endereco", enderecoEValor[i]);
            celula.put("existe", true);
            String valor = enderecoEValor[i + 1];
            try {
                celula.put("valor", Double.parseDouble(valor));
                celula.put("tipo", "numero");
            } catch (NumberFormatException naoEraNumero) {
                celula.put("valor", valor);
                celula.put("tipo", "texto");
            }
        }
        return new ConsultaArquivosPc.Resposta(dados, "");
    }

    private static DadosDoCard.Grafico grafico(CardTela card) {
        return ((DadosDoCard.Pesquisa) card.dados()).grafico();
    }

    @Test
    @DisplayName("sem HUD aberta não abre card nem consulta fonte nenhuma")
    void semTelaAberta() throws Exception {
        ResultadoFerramenta resultado = new GraficoDeDadosFerramenta(PresencaDaHud.semTela(), JSON,
                PROPS, new ClimaService(null, JSON, PROPS) {
                    @Override
                    public List<PrevisaoDia> previsaoDias(int quantos) {
                        throw new AssertionError("não devia consultar o clima sem tela aberta");
                    }
                }, null, null).executarDetalhado(entradaDeClima(), DO_TELEGRAM);

        assertEquals(FerramentaDeTela.SEM_TELA, resultado.texto());
        assertTrue(resultado.cards().isEmpty(),
                "abrir card para ninguém deixaria o modelo prometendo o que não aconteceu");
    }

    @Test
    @DisplayName("o schema não tem onde o modelo escrever um número")
    void schemaNaoAceitaValores() {
        JsonNode campos = com(() -> 1, semana()).esquemaEntrada().path("properties");

        // É a ausência destes quatro campos que faz a ferramenta valer o nome.
        assertTrue(campos.path("eixoX").isMissingNode(), "eixoX não pode vir do modelo");
        assertTrue(campos.path("series").isMissingNode(), "series não pode vir do modelo");
        assertTrue(campos.path("valores").isMissingNode());
        assertTrue(campos.path("origem").isMissingNode(), "quem marca a origem é o Java");

        ArrayNode fontes = (ArrayNode) campos.path("fonte").path("enum");
        assertEquals(GraficoDeDadosFerramenta.FONTES.size(), fontes.size());
        assertEquals("clima_7_dias", fontes.get(0).stringValue());
        assertTrue(campos.path("titulo").path("description").stringValue().contains("CONCLUSÃO"));
    }

    @Test
    @DisplayName("o clima vira dois cards: temperatura em °C e chuva em %, com origem 'servidor'")
    void climaViraDoisCards() throws Exception {
        ResultadoFerramenta resultado =
                com(() -> 1, semana()).executarDetalhado(entradaDeClima(), DO_TELEGRAM);

        assertEquals(2, resultado.cards().size(), "unidade diferente pede card diferente");
        CardTela temperatura = resultado.cards().getFirst();
        CardTela chuva = resultado.cards().getLast();

        assertEquals("A semana esquenta a partir de quarta", temperatura.titulo());
        assertEquals("telegram", temperatura.origem());
        assertEquals("servidor", grafico(temperatura).origem());
        assertEquals("linha", grafico(temperatura).tipo());
        assertEquals("°C", grafico(temperatura).unidade());
        assertEquals(List.of("seg", "ter", "qua", "qui", "sex", "sáb", "dom"),
                grafico(temperatura).eixoX());
        assertEquals(2, grafico(temperatura).series().size());
        assertEquals("máxima", grafico(temperatura).series().getFirst().nome());
        assertEquals(28.0, grafico(temperatura).series().getFirst().valores().get(4), 0.001);
        assertEquals(12.1, grafico(temperatura).series().getLast().valores().getFirst(), 0.001);
        // O destaque cai no dia mais quente, que é o quinto.
        assertEquals(4, grafico(temperatura).destaque().indice());

        assertEquals("barras", grafico(chuva).tipo());
        assertEquals("%", grafico(chuva).unidade());
        assertEquals("servidor", grafico(chuva).origem());
        assertEquals(90.0, grafico(chuva).series().getFirst().valores().getLast(), 0.001);

        // O resumo que vai ao modelo também é escrito em Java, com os extremos da série.
        assertTrue(resultado.texto().contains("Máxima de 28 °C"), resultado.texto());
        assertTrue(resultado.texto().contains(temperatura.id()));
        assertTrue(resultado.texto().contains(chuva.id()));
    }

    @Test
    @DisplayName("sem a chance de chuva em todos os dias, só o card de temperatura")
    void chuvaIncompletaDeixaUmCard() throws Exception {
        List<PrevisaoDia> dias = new ArrayList<>(semana());
        PrevisaoDia terceiro = dias.get(2);
        dias.set(2, new PrevisaoDia(terceiro.data(), terceiro.cidade(), terceiro.minima(),
                terceiro.maxima(), null, terceiro.ventoMaxKmh(), terceiro.descricao()));

        ResultadoFerramenta resultado = com(() -> 1, dias).executarDetalhado(entradaDeClima(), DO_TELEGRAM);

        assertEquals(1, resultado.cards().size(),
                "barra faltando no meio de uma comparação é pior que comparação nenhuma");
        assertEquals("°C", grafico(resultado.cards().getFirst()).unidade());
        assertTrue(resultado.texto().contains("chance de chuva não veio"), resultado.texto());
    }

    @Test
    @DisplayName("dia sem temperatura corta a série: linha com buraco não é representável")
    void diaIncompletoCortaASerie() throws Exception {
        List<PrevisaoDia> dias = new ArrayList<>(semana().subList(0, 3));
        dias.add(new PrevisaoDia(LocalDate.of(2026, 10, 22), "Curitiba", null, null, 30, null, ""));
        dias.add(semana().get(4));

        ResultadoFerramenta resultado = com(() -> 1, dias).executarDetalhado(entradaDeClima(), DO_TELEGRAM);

        assertEquals(3, grafico(resultado.cards().getFirst()).eixoX().size());
        assertEquals(3, grafico(resultado.cards().getFirst()).series().getFirst().valores().size());
    }

    @Test
    @DisplayName("um dia completo só não dá linha: responde sem prometer card")
    void previsaoCurtaDemais() throws Exception {
        List<PrevisaoDia> dias = List.of(semana().getFirst(),
                new PrevisaoDia(LocalDate.of(2026, 10, 20), "Curitiba", null, null, 30, null, ""));

        ResultadoFerramenta resultado = com(() -> 1, dias).executarDetalhado(entradaDeClima(), DO_TELEGRAM);

        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("sem prometer card"), resultado.texto());
    }

    @Test
    @DisplayName("a planilha vira um card, com rótulos e nome de série vindos do arquivo")
    void planilhaLeCelulas() throws Exception {
        ResultadoFerramenta resultado = comPlanilha(celulas(
                "A1", "mês", "B1", "faturamento",
                "A2", "jan", "B2", "1200.5",
                "A3", "fev", "B3", "1800",
                "A4", "mar", "B4", "2400"))
                .executarDetalhado(entradaDePlanilha(), DO_TELEGRAM);

        assertEquals(1, resultado.cards().size());
        DadosDoCard.Grafico grafico = grafico(resultado.cards().getFirst());
        assertEquals("servidor", grafico.origem());
        assertEquals("barras", grafico.tipo());
        assertEquals("R$", grafico.unidade());
        assertEquals(List.of("jan", "fev", "mar"), grafico.eixoX());
        // O nome da série sai do cabeçalho da planilha, não do que o modelo chamaria a coluna.
        assertEquals("faturamento", grafico.series().getFirst().nome());
        assertEquals(List.of(1200.5, 1800.0, 2400.0), grafico.series().getFirst().valores());

        // O pedido ao agente cobre o cabeçalho junto, numa ida só.
        assertEquals(1, pedidoVisto.path("linhaInicial").intValue());
        assertEquals(4, pedidoVisto.path("linhaFinal").intValue());
        assertEquals(1, pedidoVisto.path("colunaInicial").intValue());
        assertEquals(2, pedidoVisto.path("colunaFinal").intValue());
        assertEquals("Vendas", pedidoVisto.path("aba").stringValue());
    }

    @Test
    @DisplayName("célula de texto na coluna de valores é recusada nomeando o endereço")
    void celulaNaoNumericaRecusada() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> comPlanilha(celulas(
                        "A1", "mês", "B1", "faturamento",
                        "A2", "jan", "B2", "1200",
                        "A3", "fev", "B3", "sem dados",
                        "A4", "mar", "B4", "2400"))
                        .executarDetalhado(entradaDePlanilha(), DO_TELEGRAM));

        assertTrue(erro.getMessage().contains("B3"), erro.getMessage());
        assertTrue(erro.getMessage().contains("sem dados"), erro.getMessage());
        assertTrue(erro.getMessage().contains("não é número"), erro.getMessage());
    }

    @Test
    @DisplayName("célula vazia na coluna de valores também é recusada: zero seria uma afirmação")
    void celulaVaziaRecusada() {
        IllegalArgumentException erro = assertThrows(IllegalArgumentException.class,
                () -> comPlanilha(celulas(
                        "A1", "mês", "B1", "faturamento",
                        "A2", "jan", "B2", "1200",
                        "A3", "fev",
                        "A4", "mar", "B4", "2400"))
                        .executarDetalhado(entradaDePlanilha(), DO_TELEGRAM));

        assertTrue(erro.getMessage().contains("B3"), erro.getMessage());
    }

    @Test
    @DisplayName("PC offline devolve o recado do agente, sem card")
    void pcOffline() throws Exception {
        ResultadoFerramenta resultado = comPlanilha(
                new ConsultaArquivosPc.Resposta(null, "O PC está offline; os arquivos originais locais estão indisponíveis."))
                .executarDetalhado(entradaDePlanilha(), DO_TELEGRAM);

        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("offline"));
    }

    @Test
    @DisplayName("mais linhas que o gráfico aceita é recusado antes de falar com o PC")
    void intervaloGrandeDemaisNaoChamaOPc() {
        ObjectNode entrada = entradaDePlanilha();
        entrada.put("linhaFinal", 20);

        GraficoDeDadosFerramenta ferramenta = new GraficoDeDadosFerramenta(() -> 1, JSON, PROPS,
                new ClimaService(null, JSON, PROPS), null, null) {
            @Override
            protected ConsultaArquivosPc.Resposta lerPlanilha(ObjectNode pedido,
                    OuvinteProgresso progresso) {
                throw new AssertionError("não devia chamar o PC com intervalo inválido");
            }
        };

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ferramenta.executarDetalhado(entrada, DO_TELEGRAM))
                .getMessage().contains("cabe 12 categorias"));
    }

    @Test
    @DisplayName("fonte desconhecida, coluna que não é letra e id ausente são recusados")
    void entradaInvalida() {
        GraficoDeDadosFerramenta ferramenta = com(() -> 1, semana());

        ObjectNode outraFonte = entradaDeClima();
        outraFonte.put("fonte", "cotacao_do_dolar");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ferramenta.executarDetalhado(outraFonte, DO_TELEGRAM))
                .getMessage().contains("fonte deve ser uma de"));

        ObjectNode semId = entradaDePlanilha();
        semId.remove("arquivoId");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ferramenta.executarDetalhado(semId, DO_TELEGRAM))
                .getMessage().contains("arquivoId"));

        ObjectNode colunaEstranha = entradaDePlanilha();
        colunaEstranha.put("colunaRotulos", "3");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> ferramenta.executarDetalhado(colunaEstranha, DO_TELEGRAM))
                .getMessage().contains("letra da coluna"));
    }

    // ---------------------------------------------------------------- custo

    private static ObjectNode entradaDeCusto() {
        ObjectNode entrada = JSON.createObjectNode();
        entrada.put("fonte", "custo_30_dias");
        entrada.put("titulo", "O classificador é metade da conta");
        entrada.put("resumo", "O gasto do mês vem quase todo da conversa.");
        return entrada;
    }

    /** Um dia da série, com o total somado das origens — é assim que o medidor o monta. */
    private static MedidorDeCusto.DiaDeCusto diaDeCusto(LocalDate dia, Map<Origem, Double> custos) {
        double total = 0;
        for (double custo : custos.values()) {
            total += custo;
        }
        return new MedidorDeCusto.DiaDeCusto(dia, total, 0, 0, 0, 0, 0, false, custos);
    }

    private static GraficoDeDadosFerramenta comCusto(List<MedidorDeCusto.DiaDeCusto> dias) {
        MedidorDeCusto medidor = mock(MedidorDeCusto.class);
        when(medidor.serieDiaria(GraficoDeDadosFerramenta.DIAS_DO_CUSTO)).thenReturn(dias);
        return new GraficoDeDadosFerramenta(() -> 1, JSON, PROPS,
                new ClimaService(null, JSON, PROPS), null, medidor);
    }

    private static LocalDate hoje() {
        return LocalDate.now(PROPS.zona());
    }

    @Test
    @DisplayName("30 dias viram 6 barras de 5 dias, empilhadas por origem e marcadas 'servidor'")
    void custoViraSeisBarras() throws Exception {
        List<MedidorDeCusto.DiaDeCusto> dias = new ArrayList<>(30);
        for (int i = 0; i < 30; i++) {
            dias.add(diaDeCusto(hoje().minusDays(29L - i), Map.of(Origem.CONVERSA, 0.01)));
        }

        ResultadoFerramenta resultado =
                comCusto(dias).executarDetalhado(entradaDeCusto(), DO_TELEGRAM);

        DadosDoCard.Grafico g = grafico(resultado.cards().getFirst());
        assertEquals("barras_empilhadas", g.tipo());
        assertEquals("servidor", g.origem());
        assertEquals("US$", g.unidade());
        assertEquals(6, g.eixoX().size(), "trinta pontos não cabem nas doze da barra empilhada");
        assertEquals(1, g.series().size());
        assertEquals("conversa", g.series().getFirst().nome());
        for (double valor : g.series().getFirst().valores()) {
            assertEquals(0.05d, valor, 1e-9, "cinco dias de um centavo por barra");
        }
        // A última barra termina hoje: é assim que o eixo diz de quando é a conta.
        assertTrue(g.eixoX().getLast().endsWith(
                hoje().format(DateTimeFormatter.ofPattern("dd/MM"))), g.eixoX().toString());
        assertTrue(resultado.texto().contains("US$ 0,30"), resultado.texto());
        assertTrue(resultado.texto().contains("conversa"), resultado.texto());
    }

    @Test
    @DisplayName("mais de quatro origens: as três maiores e o resto somado em 'outras'")
    void origensDemaisViramOutras() throws Exception {
        Map<Origem, Double> custos = new EnumMap<>(Origem.class);
        custos.put(Origem.CONVERSA, 1.0);
        custos.put(Origem.COMPUTER_USE, 0.5);
        custos.put(Origem.BRIEFING, 0.25);
        custos.put(Origem.ANOTADOR, 0.1);
        custos.put(Origem.CLASSIFICADOR, 0.05);
        custos.put(Origem.INICIALIZACAO, 0.01);

        DadosDoCard.Grafico g = grafico(comCusto(List.of(diaDeCusto(hoje(), custos)))
                .executarDetalhado(entradaDeCusto(), DO_TELEGRAM).cards().getFirst());

        assertEquals(List.of("conversa", "computer_use", "briefing", "outras"),
                g.series().stream().map(DadosDoCard.Grafico.Serie::nome).toList());
        assertEquals(0.16d, g.series().getLast().valores().getFirst(), 1e-9,
                "'outras' é a soma das menores, não um descarte");
    }

    @Test
    @DisplayName("quatro origens cabem uma a uma, sem 'outras'")
    void quatroOrigensNaoViramOutras() throws Exception {
        Map<Origem, Double> custos = new EnumMap<>(Origem.class);
        custos.put(Origem.CONVERSA, 1.0);
        custos.put(Origem.BRIEFING, 0.5);
        custos.put(Origem.ANOTADOR, 0.25);
        custos.put(Origem.CLASSIFICADOR, 0.1);

        DadosDoCard.Grafico g = grafico(comCusto(List.of(diaDeCusto(hoje(), custos)))
                .executarDetalhado(entradaDeCusto(), DO_TELEGRAM).cards().getFirst());

        assertEquals(4, g.series().size());
        assertFalse(g.series().stream().anyMatch(s -> "outras".equals(s.nome())));
    }

    @Test
    @DisplayName("bloco sem dia registrado sai do eixo: zero ali diria 'não gastei'")
    void blocoSemDiaSaiDoEixo() throws Exception {
        List<MedidorDeCusto.DiaDeCusto> dias = List.of(
                diaDeCusto(hoje().minusDays(29), Map.of(Origem.CONVERSA, 0.2)),
                diaDeCusto(hoje(), Map.of(Origem.CONVERSA, 0.3)));

        DadosDoCard.Grafico g = grafico(comCusto(dias)
                .executarDetalhado(entradaDeCusto(), DO_TELEGRAM).cards().getFirst());

        assertEquals(2, g.eixoX().size(), "os quatro blocos do meio não tiveram movimento");
        assertEquals(List.of(0.2d, 0.3d), g.series().getFirst().valores());
    }

    @Test
    @DisplayName("mês sem gasto nenhum responde em texto, sem prometer card")
    void custoSemGastoNaoAbreCard() throws Exception {
        ResultadoFerramenta resultado =
                comCusto(List.of()).executarDetalhado(entradaDeCusto(), DO_TELEGRAM);

        assertTrue(resultado.cards().isEmpty());
        assertTrue(resultado.texto().contains("Não há gasto registrado"), resultado.texto());
    }

    @Test
    @DisplayName("o campo 'tipo' do schema é só da planilha: rosca e empilhada não entram por lá")
    void schemaDoTipoEhSoDaPlanilha() {
        JsonNode campos = com(() -> 1, semana()).esquemaEntrada().path("properties");

        ArrayNode tipos = (ArrayNode) campos.path("tipo").path("enum");
        assertEquals(List.of("linha", "barras"),
                tipos.valueStream().map(JsonNode::stringValue).toList());
        assertTrue(campos.path("fonte").path("enum").toString().contains("custo_30_dias"));
    }

    @Test
    @DisplayName("as letras de coluna seguem a conta do Excel")
    void letrasDeColuna() {
        assertEquals(1, GraficoDeDadosFerramenta.coluna("A", "c"));
        assertEquals(2, GraficoDeDadosFerramenta.coluna("b", "c"));
        assertEquals(27, GraficoDeDadosFerramenta.coluna("AA", "c"));
        assertEquals("A", GraficoDeDadosFerramenta.letra(1));
        assertEquals("Z", GraficoDeDadosFerramenta.letra(26));
        assertEquals("AA", GraficoDeDadosFerramenta.letra(27));
        assertEquals("AB", GraficoDeDadosFerramenta.letra(28));
    }

    @Test
    @DisplayName("sem ouvinte de progresso a ferramenta não abre card")
    void executarSemOuvinte() throws Exception {
        assertFalse(com(() -> 1, semana()).executar(entradaDeClima()).isEmpty());
        assertEquals(FerramentaDeTela.SEM_TELA, com(() -> 1, semana()).executar(entradaDeClima()));
    }
}
