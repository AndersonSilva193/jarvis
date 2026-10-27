package dev.jarvis.custo;

import static dev.jarvis.custo.MedidorDeCustoCalculoTest.props;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import dev.jarvis.util.ArquivoAtomico;
import tools.jackson.databind.json.JsonMapper;

/** A virada do dia no fuso configurado, a leitura depois do restart e o expurgo dos 90 dias. */
class MedidorDeCustoDiaTest {

    @TempDir
    Path pasta;

    private final TelegramClient telegram = mock(TelegramClient.class);

    /** Um "restart do container": medidor novo, mesma pasta, mesmo relógio. */
    private final java.util.List<MedidorDeCusto> criados = new java.util.ArrayList<>();

    /** Fecha o executor de gravacao: sem isso o @TempDir nao consegue ser apagado no Windows. */
    @org.junit.jupiter.api.AfterEach
    void fechar() {
        criados.forEach(MedidorDeCusto::fechar);
    }

    private MedidorDeCusto subir(RelogioFalso relogio) {
        var novo = new MedidorDeCusto(props("modelo-a=1/5/1.25/0.1", ""), telegram,
                JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    void ferramentasSaoContadasPorDiaEPersistemSemCustoDeModelo() {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var antes = subir(relogio);
        antes.registrarFerramenta("clima");
        antes.registrarFerramenta("clima");
        antes.registrarFerramenta("criar_lembrete");
        antes.aguardar();

        var depois = subir(relogio);
        assertEquals(java.util.Map.of("clima", 2L, "criar_lembrete", 1L),
                depois.hoje().ferramentasMaisUsadas());
        relogio.avancar(Duration.ofDays(1));
        depois.registrarFerramenta("noticias");
        assertEquals(java.util.Map.of("noticias", 1L), depois.hoje().ferramentasMaisUsadas());
        assertEquals(2L, depois.periodo(2).ferramentasMaisUsadas().get("clima"));
    }

    @Test
    void relatorioMostraApenasDezFerramentasEDesempataPeloNome() {
        var medidor = subir(new RelogioFalso("2026-10-22T15:00:00Z"));
        for (int i = 11; i >= 0; i--) medidor.registrarFerramenta("f" + i);
        assertEquals(java.util.List.of("f0", "f1", "f10", "f11", "f2", "f3", "f4",
                "f5", "f6", "f7"),
                java.util.List.copyOf(medidor.hoje().ferramentasMaisUsadas().keySet()));
    }

    @Test
    @DisplayName("o dia é o do fuso configurado, não o do UTC")
    void oDiaEhODoFuso() {
        // 02:30 UTC de 22/10 ainda é 23:30 de 21/10 em São Paulo (UTC-3).
        var medidor = subir(new RelogioFalso("2026-10-22T02:30:00Z"));
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.aguardar();

        assertTrue(Files.exists(pasta.resolve("2026-10-21.json")),
                "o consumo pertence ao dia 21 em São Paulo, não ao 22 do UTC");
        assertFalse(Files.exists(pasta.resolve("2026-10-22.json")));
        assertEquals(LocalDate.parse("2026-10-21"), medidor.hoje().ate());
    }

    @Test
    @DisplayName("passada a meia-noite local, o gasto novo não entra na conta de ontem")
    void viradaSeparaOsDois() {
        var relogio = new RelogioFalso("2026-10-22T02:30:00Z");
        var medidor = subir(relogio);
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);

        // Mais uma hora: 03:30 UTC = 00:30 de 22/10 em São Paulo.
        relogio.avancar(Duration.ofHours(1));
        medidor.registrar(Origem.BRIEFING, "modelo-a", 2_000_000, 0, 0, 0, 0);
        medidor.aguardar();

        assertTrue(Files.exists(pasta.resolve("2026-10-21.json")));
        assertTrue(Files.exists(pasta.resolve("2026-10-22.json")));
        assertEquals(2.0, medidor.hoje().totalUsd(), 1e-9, "hoje não carrega o de ontem");
        assertEquals(3.0, medidor.periodo(2).totalUsd(), 1e-9, "os dois dias juntos, sim");
    }

    @Test
    @DisplayName("o gasto de hoje volta do disco depois de um restart")
    void voltaDepoisDoRestart() {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var antes = subir(relogio);
        antes.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 200_000, 0, 0, 4);
        antes.aguardar();

        var depois = subir(relogio);
        assertEquals(antes.hoje().totalUsd(), depois.hoje().totalUsd(), 1e-9);
        assertEquals(4, depois.hoje().buscasWeb());
        // 1,00 de entrada + 1,00 de saída + 0,04 das quatro buscas, tudo no balde da conversa.
        assertEquals(2.04, depois.hoje().porOrigem().get(Origem.CONVERSA), 1e-9);
    }

    @Test
    @DisplayName("quem reaparece depois do restart continua marcado como sem preço")
    void semPrecoSobreviveAoRestart() {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var antes = subir(relogio);
        antes.registrar(Origem.COMPUTER_USE, "gpt-sem-preco", 10_000, 500, 0, 0, 0);
        antes.aguardar();

        assertEquals(java.util.List.of("gpt-sem-preco"), subir(relogio).hoje().modelosSemPreco());
    }

    @Test
    @DisplayName("o expurgo leva o dia vencido e o .bak dele, e não toca no que está no prazo")
    void expurgoDeNoventaDias() throws IOException {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        LocalDate hoje = LocalDate.parse("2026-10-22");
        Path vencido = pasta.resolve(hoje.minusDays(91) + ".json");
        Path limite = pasta.resolve(hoje.minusDays(90) + ".json");
        Path noPrazo = pasta.resolve(hoje.minusDays(89) + ".json");
        Path alheio = pasta.resolve("anotacoes.txt");
        for (Path arquivo : java.util.List.of(vencido, limite, noPrazo)) {
            ArquivoAtomico.gravar(arquivo, "{}");
            ArquivoAtomico.gravar(arquivo, "{}");   // a segunda gravação é que cria o .bak
        }
        Files.writeString(alheio, "não é nosso");

        var medidor = subir(relogio);
        medidor.naPartida();
        medidor.aguardar();

        assertFalse(Files.exists(vencido), "o dia de 91 dias tinha de sair");
        assertFalse(Files.exists(ArquivoAtomico.backupDe(vencido)), "o .bak dele também");
        assertFalse(Files.exists(limite), "hoje e os 89 dias anteriores são exatamente 90 datas");
        assertTrue(Files.exists(noPrazo), "o de 89 dias fica");
        assertTrue(Files.exists(alheio), "arquivo que não é nosso não se apaga");
    }

    @Test
    @DisplayName("a série sai em ordem e pula o dia em que o Jarvis estava desligado")
    void serieOmiteDiaSemArquivo() throws IOException {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        // Um dia com movimento, três dias atrás; o de anteontem e o de ontem não existem.
        ArquivoAtomico.gravar(pasta.resolve("2026-10-19.json"), """
                {"dia":"2026-10-19","fuso":"America/Sao_Paulo",
                 "porModelo":{"modelo-a":{"entrada":1000000,"custoUsd":1.0}}}
                """);
        var medidor = subir(relogio);
        medidor.registrar(Origem.CONVERSA, "modelo-a", 2_000_000, 0, 0, 0, 0);
        medidor.aguardar();

        var serie = medidor.serieDiaria(7);
        assertEquals(2, serie.size(), "só os dias com movimento entram");
        assertEquals(LocalDate.parse("2026-10-19"), serie.get(0).dia(), "do mais antigo ao recente");
        assertEquals(LocalDate.parse("2026-10-22"), serie.get(1).dia());
        assertEquals(2.0, serie.get(1).totalUsd(), 1e-9);
    }

    @Test
    @DisplayName("a série traz o custo do dia dividido por origem, que é o que a barra empilha")
    void serieTrazOCustoPorOrigem() {
        var medidor = subir(new RelogioFalso("2026-10-22T15:00:00Z"));
        medidor.registrar(Origem.CONVERSA, "modelo-a", 2_000_000, 0, 0, 0, 0);
        medidor.registrar(Origem.CLASSIFICADOR, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.aguardar();

        var dia = medidor.serieDiaria(7).getLast();

        assertEquals(2.0, dia.porOrigem().get(Origem.CONVERSA), 1e-9);
        assertEquals(1.0, dia.porOrigem().get(Origem.CLASSIFICADOR), 1e-9);
        assertEquals(3.0, dia.totalUsd(), 1e-9, "as partes somam o total do dia");
        assertFalse(dia.porOrigem().containsKey(Origem.BRIEFING), "origem sem gasto não entra");
    }

    @Test
    @DisplayName("arquivo do dia corrompido cai no .bak em vez de perder a conta")
    void corrompidoCaiNoBak() throws IOException {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var antes = subir(relogio);
        antes.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        antes.aguardar();
        antes.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        antes.aguardar();   // a segunda gravação deixa o .bak com o primeiro milhão

        Path doDia = pasta.resolve("2026-10-22.json");
        assertTrue(Files.exists(ArquivoAtomico.backupDe(doDia)));
        Files.writeString(doDia, "{isso não é json");

        assertEquals(1.0, subir(relogio).hoje().totalUsd(), 1e-9, "o .bak tinha um milhão só");
    }

    @Test
    @DisplayName("virarODia fecha o dia que acabou sem criar arquivo de dia vazio")
    void viradaAgendadaNaoCriaArquivoVazio() {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var medidor = subir(relogio);
        medidor.virarODia();
        medidor.aguardar();

        assertFalse(Files.exists(pasta.resolve("2026-10-22.json")),
                "dia sem nenhuma chamada ao modelo não merece arquivo");
    }

    @Test
    @DisplayName("o cron que chega depois do primeiro consumo da manhã preserva o dia novo")
    void cronAtrasadoNaoFechaODiaNovo() {
        var relogio = new RelogioFalso("2026-10-22T03:00:01Z");
        var medidor = subir(relogio);
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);

        medidor.virarODia();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.aguardar();

        assertEquals(2.0, medidor.hoje().totalUsd(), 1e-9);
    }

    @Test
    @DisplayName("falha ao gravar ontem continua pendente depois da virada e é repetida na data certa")
    void falhaDeOntemEhRepetidaDepoisDaVirada() throws IOException {
        Path bloqueada = pasta.resolve("diario");
        Files.writeString(bloqueada, "impede criar a pasta");
        var relogio = new RelogioFalso("2026-10-22T02:30:00Z");
        var medidor = new MedidorDeCusto(props("modelo-a=1/5/1.25/0.1", ""), telegram,
                JsonMapper.builder().build(), relogio, bloqueada);
        criados.add(medidor);

        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000_000, 0, 0, 0, 0);
        medidor.aguardar();
        relogio.avancar(Duration.ofHours(1));
        medidor.registrar(Origem.BRIEFING, "modelo-a", 2_000_000, 0, 0, 0, 0);
        medidor.aguardar();
        assertEquals(3.0, medidor.periodo(2).totalUsd(), 1e-9,
                "o relatório usa os dois retratos pendentes mesmo sem disco");

        Files.delete(bloqueada);
        Files.createDirectory(bloqueada);
        medidor.gravarPendente();
        medidor.aguardar();
        assertTrue(Files.exists(bloqueada.resolve("2026-10-21.json")));
        assertTrue(Files.exists(bloqueada.resolve("2026-10-22.json")));
    }
    /** O diário em disco, lido direto: o Relatorio não expõe a contagem de chamadas. */
    private EstadoDoDia doDisco(LocalDate dia) {
        return new CustoDiarioEmArquivo(pasta, JsonMapper.builder().build(),
                java.time.ZoneId.of("America/Sao_Paulo")).carregar(dia);
    }

    @Test
    @DisplayName("a contagem de chamadas sobrevive ao restart, nos dois baldes")
    void chamadasVoltamDoDisco() {
        var relogio = new RelogioFalso("2026-10-22T15:00:00Z");
        var antes = subir(relogio);
        antes.registrar(Origem.CONVERSA, "modelo-a", 1_000, 300, 0, 0, 0);
        antes.registrar(Origem.CONVERSA, "modelo-a", 1_000, 500, 0, 0, 0);
        antes.registrar(Origem.BRIEFING, "modelo-a", 1_000, 90, 0, 0, 0);
        antes.aguardar();

        var dia = doDisco(LocalDate.parse("2026-10-22"));
        // É este par — saída e chamadas — que dá a média por origem: 800/2 contra 90/1.
        assertEquals(2, dia.porOrigem().get(Origem.CONVERSA).chamadas);
        assertEquals(800, dia.porOrigem().get(Origem.CONVERSA).saida);
        assertEquals(1, dia.porOrigem().get(Origem.BRIEFING).chamadas);
        assertEquals(3, dia.porModelo().get("modelo-a").chamadas,
                "os dois baldes recebem os mesmos números, e a contagem não é exceção");
    }

    @Test
    @DisplayName("dia gravado antes do contador volta com chamadas zero, sem migração de arquivo")
    void arquivoAntigoVoltaSemChamadas() throws IOException {
        ArquivoAtomico.gravar(pasta.resolve("2026-10-20.json"), """
                {"dia":"2026-10-20","fuso":"America/Sao_Paulo",
                 "porOrigem":{"conversa":{"entrada":1000,"saida":400,"custoUsd":1.0}},
                 "porModelo":{"modelo-a":{"entrada":1000,"saida":400,"custoUsd":1.0}}}
                """);

        var dia = doDisco(LocalDate.parse("2026-10-20"));
        assertEquals(400, dia.porOrigem().get(Origem.CONVERSA).saida, "os tokens continuam lá");
        assertEquals(0, dia.porOrigem().get(Origem.CONVERSA).chamadas,
                "sem o campo, zero — e um dia com zero chamadas não afirma saída média nenhuma");
    }
}
