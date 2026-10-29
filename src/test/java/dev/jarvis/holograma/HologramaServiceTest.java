package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.ia.ErroDaApiDoModelo;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.Cancelamento;
import dev.jarvis.ia.provedor.DisponibilidadeLocal;
import dev.jarvis.ia.provedor.FalhaLocal;
import dev.jarvis.ia.provedor.PedidoAoModelo;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.json.JsonMapper;

/** A geração com stream: peça na hora, limite, reserva, prazo e cliente que vai embora. */
class HologramaServiceTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String META = "{\"t\":\"meta\",\"nome\":\"Cubo\",\"cat\":\"teste\",\"desc\":\"um cubo\"}\n";
    private static final String PECA = "{\"t\":\"p\",\"id\":\"cubo\",\"n\":\"Cubo\",\"g\":\"corpo\",\"f\":\"box\","
            + "\"p\":[0,0,0],\"r\":[0,0,0],\"s\":[1,1,1],\"c\":\"#38bdf8\",\"e\":[0,1,0]}\n";
    private static final String FIM = "{\"t\":\"fim\"}";

    private HologramaService servico;

    @AfterEach
    void fechar() {
        if (servico != null) servico.fechar();
    }

    @Test
    void aPecaSaiAntesDeOModeloTerminar() throws Exception {
        Emissor emissor = new Emissor();
        AtomicBoolean pecaNaTelaNoMeio = new AtomicBoolean();
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        anthropic.depoisDoDelta = i -> {
            if (i == 1) pecaNaTelaNoMeio.set(emissor.corpo().contains("event:peca"));
        };
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.RAPIDO, emissor);
        emissor.esperar();

        assertTrue(pecaNaTelaNoMeio.get(), "a peça só saiu no fim");
        // O fim veio sem quebra de linha: é o resto do buffer, e não há aviso de fim faltando.
        assertEquals(List.of("meta", "peca", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains("\"pecas\":1,\"avisos\":0"), emissor.corpo());
    }

    @Test
    void anthropicRecebeOModeloEOTetoDoNivelSemTemperatura() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, FIM));
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.DETALHADO, emissor);
        emissor.esperar();

        var opcoes = anthropic.ultimo.opcoes();
        assertEquals("modelo-holograma", opcoes.modelo());
        // Cada nível tem o seu teto; o detalhado não é mais preso nos 8000 do normal.
        assertEquals(16_000, opcoes.maxTokens());
        assertEquals(null, opcoes.temperatura());
        assertEquals("Gere: cubo", anthropic.ultimo.mensagens().get(0).texto());
        assertTrue(anthropic.ultimo.sistema().blocos().get(0).texto().contains("OBJETO: cubo"));
        assertTrue(anthropic.ultimo.sistema().blocos().get(0).texto().contains("NÍVEL DE DETALHE: detalhado"));
    }

    @Test
    void oLimiteDePecasCortaOModelo() throws Exception {
        Emissor emissor = new Emissor();
        String sessenta = "{\"t\":\"p\",\"id\":\"dente\",\"f\":\"box\",\"rep\":{\"n\":60,\"d\":[0.01,0,0]}}\n";
        Falso anthropic = new Falso(List.of(META, sessenta, sessenta, sessenta, sessenta, sessenta, sessenta));
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("engrenagem", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(anthropic.cortado, "o stream do modelo não foi fechado");
        assertTrue(emissor.corpo().contains("\"pecas\":250"), emissor.corpo());
        assertFalse(emissor.eventos().contains("erro"));
        assertEquals("fim", emissor.eventos().get(emissor.eventos().size() - 1));
    }

    @Test
    void localQueCaiAntesDaPrimeiraPecaPassaAoClaude() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META));
        local.falha = new FalhaLocal("timeout", "sem escrever");
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(1, local.chamadas);
        assertEquals(1, anthropic.chamadas);
        assertEquals(0.4, local.ultimo.opcoes().temperatura());
        assertTrue(emissor.eventos().contains("peca"));
        assertFalse(emissor.eventos().contains("erro"));
    }

    @Test
    void localQueCaiDepoisDaPrimeiraPecaNaoTemReserva() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META, PECA));
        local.falha = new FalhaLocal("timeout", "sem escrever");
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, anthropic.chamadas);
        assertEquals(List.of("meta", "peca", "erro", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_FALHA), emissor.corpo());
    }

    @Test
    void localIndisponivelVaiDiretoAoClaude() throws Exception {
        Emissor emissor = new Emissor();
        Falso local = new Falso(List.of(META, PECA, FIM));
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", false);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, local.chamadas);
        assertEquals(1, anthropic.chamadas);
    }

    @Test
    void oPrazoFechaComErroEFimEMantemAsPecas() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA));
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true,
                Duration.ofMillis(200), Duration.ofSeconds(10));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(anthropic.cortado);
        assertEquals(List.of("meta", "peca", "erro", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_TEMPO), emissor.corpo());
    }

    @Test
    void oClienteQueSaiCortaOModeloAntesDaPrimeiraPeca() throws Exception {
        Emissor emissor = new Emissor();
        emissor.foiEmbora = true;
        Falso anthropic = new Falso(List.of());
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true,
                Duration.ofSeconds(10), Duration.ofMillis(20));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);

        assertTrue(anthropic.corte.await(5, TimeUnit.SECONDS), "o ping não percebeu o cliente fora");
    }

    @Test
    void umaGeracaoNovaSubstituiAAnterior() throws Exception {
        Emissor primeiro = new Emissor();
        Falso lento = new Falso(List.of(META));
        lento.esperarCorte = true;
        servico = servico(lento, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, primeiro);
        assertTrue(lento.comecou.await(5, TimeUnit.SECONDS));
        servico.gerar("esfera", DetalheHolograma.NORMAL, new Emissor());
        primeiro.esperar();

        assertTrue(primeiro.corpo().contains(HologramaService.TEXTO_SUBSTITUIDO), primeiro.corpo());
    }

    // ---- gerador por pedido e reserva ---------------------------------------------------------

    @Test
    void oPedidoEscolheOGeradorSobrescrevendoOConfigurado() throws Exception {
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true);

        Emissor paraLocal = new Emissor();
        servico.gerar("cubo", DetalheHolograma.NORMAL, "local", paraLocal);
        paraLocal.esperar();
        assertEquals(1, local.chamadas);
        assertEquals(0, anthropic.chamadas);

        Emissor paraHaiku = new Emissor();
        servico.gerar("cubo", DetalheHolograma.NORMAL, "anthropic", paraHaiku);
        paraHaiku.esperar();
        assertEquals(1, local.chamadas);
        assertEquals(1, anthropic.chamadas);
    }

    @Test
    void semPedidoValeOConfigurado() throws Exception {
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "local", true);

        Emissor emissor = new Emissor();
        servico.gerar("cubo", DetalheHolograma.NORMAL, "", emissor);
        emissor.esperar();

        assertEquals(1, local.chamadas);
        assertEquals(0, anthropic.chamadas);
    }

    @Test
    void oValorDoPedidoSeLeComoHaikuLocalOuVazio() {
        assertEquals(java.util.Optional.of(""), HologramaService.provedorDoPedido(null));
        assertEquals(java.util.Optional.of(""), HologramaService.provedorDoPedido("  "));
        assertEquals(java.util.Optional.of("anthropic"), HologramaService.provedorDoPedido("Haiku"));
        assertEquals(java.util.Optional.of("anthropic"), HologramaService.provedorDoPedido("anthropic"));
        assertEquals(java.util.Optional.of("local"), HologramaService.provedorDoPedido(" LOCAL "));
        assertTrue(HologramaService.provedorDoPedido("gpt").isEmpty());
    }

    @Test
    void erroDaApiAntesDaPrimeiraPecaPassaAoLocalComAviso() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        anthropic.falha = new ErroDaApiDoModelo(402, "sem crédito");
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(1, anthropic.chamadas);
        assertEquals(1, local.chamadas);
        assertEquals(List.of("aviso", "meta", "peca", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.AVISO_HAIKU_FALHOU), emissor.corpo());
        // Quem terminou foi o local: o fim diz isso, sem tokens nem custo da API.
        assertTrue(emissor.corpo().contains("\"provedor\":\"local\""), emissor.corpo());
        assertFalse(emissor.corpo().contains("custoUsd"), emissor.corpo());
    }

    @Test
    void haikuMudoPorQuinzeSegundosPassaAoLocal() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        anthropic.esperarCorte = true;
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true, Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(100));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(anthropic.cortado, "o stream do Haiku não foi fechado");
        assertEquals(1, local.chamadas);
        assertTrue(emissor.corpo().contains(HologramaService.AVISO_HAIKU_MUDO), emissor.corpo());
        assertTrue(emissor.eventos().contains("peca"));
        assertFalse(emissor.eventos().contains("erro"));
    }

    @Test
    void haikuQueEscreveNaoEhCortadoPeloPrazoDePrimeiraResposta() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true, Duration.ofSeconds(10),
                Duration.ofSeconds(10), Duration.ofMillis(100));

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, local.chamadas);
        assertFalse(emissor.corpo().contains("troquei"), emissor.corpo());
    }

    @Test
    void haikuQueCaiNoMeioMantemAsPecasEAvisaSemTrocarDeModelo() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA));
        anthropic.falha = new ErroDaApiDoModelo(529, "sobrecarregada");
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, local.chamadas);
        assertEquals(List.of("meta", "peca", "erro", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_FALHA), emissor.corpo());
    }

    @Test
    void haikuQueFalhaComOLocalForaDeAlcanceTerminaComErro() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        anthropic.falha = new ErroDaApiDoModelo(500, "erro");
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", false);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(0, local.chamadas);
        assertEquals(List.of("meta", "erro", "fim"), emissor.eventos());
    }

    @Test
    void respostaSemNenhumaPecaPassaAoLocal() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of("Claro! Aqui está o seu objeto.\n"));
        Falso local = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, local, "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(1, local.chamadas);
        assertTrue(emissor.eventos().contains("peca"));
        assertFalse(emissor.eventos().contains("erro"));
    }

    @Test
    void respostaSemNenhumaPecaESemReservaViraErroEspecifico() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of("Desculpe, não posso.\n"));
        servico = servico(anthropic, new Falso(List.of()), "anthropic", false);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(emissor.eventos().contains("erro"), emissor.corpo());
        assertTrue(emissor.corpo().contains(HologramaService.TEXTO_SEM_PECAS), emissor.corpo());
        assertFalse(emissor.corpo().contains("\"pecas\":0,\"avisos\":2"), "não pode fechar como pronto");
    }

    @Test
    void localEscolhidoEForaDeAlcanceUsaOHaikuComAviso() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        servico = servico(anthropic, new Falso(List.of()), "local", false);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(1, anthropic.chamadas);
        assertTrue(emissor.corpo().contains(HologramaService.AVISO_LOCAL_FORA), emissor.corpo());
    }

    @Test
    void oFimTraziProvedorModeloTokensECustoEstimado() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA, FIM));
        anthropic.uso = new RespostaDoModelo.Uso(1_000, 2_000, 0, 0, 0);
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        String corpo = emissor.corpo();
        assertTrue(corpo.contains("\"provedor\":\"anthropic\""), corpo);
        assertTrue(corpo.contains("\"modelo\":\"modelo-holograma\""), corpo);
        assertTrue(corpo.contains("\"tokensEntrada\":1000"), corpo);
        assertTrue(corpo.contains("\"tokensSaida\":2000"), corpo);
        // (1000 × 0,10 + 2000 × 0,50) / 1 milhão
        assertTrue(corpo.contains("\"custoUsd\":0.0011"), corpo);
    }

    @Test
    void streamCortadoNaoInventaTokensNemCusto() throws Exception {
        Emissor emissor = new Emissor();
        String sessenta = "{\"t\":\"p\",\"id\":\"dente\",\"f\":\"box\",\"rep\":{\"n\":60,\"d\":[0.01,0,0]}}\n";
        Falso anthropic = new Falso(List.of(META, sessenta, sessenta, sessenta, sessenta, sessenta));
        anthropic.esperarCorte = true;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("engrenagem", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertTrue(emissor.corpo().contains("\"provedor\":\"anthropic\""), emissor.corpo());
        assertFalse(emissor.corpo().contains("tokensEntrada"), emissor.corpo());
        assertFalse(emissor.corpo().contains("custoUsd"), emissor.corpo());
    }

    // ---- continuação e retomada ---------------------------------------------------------------

    private static final String TOPO = "{\"t\":\"p\",\"id\":\"topo\",\"f\":\"cone\"}\n";

    @Test
    void oTetoDeTokensPedeAContinuacaoComOQueJaChegou() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        // A primeira para no teto com meia linha; a continuação repete o cubo e termina.
        anthropic.porChamada = List.of(List.of(META, PECA, "{\"t\":\"p\",\"id\":\"to"),
                List.of(PECA, TOPO, FIM));
        anthropic.motivo = RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS;
        anthropic.uso = new RespostaDoModelo.Uso(100, 200, 0, 0, 0);
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.DETALHADO, emissor);
        emissor.esperar();

        assertEquals(2, anthropic.chamadas);
        // O cubo repetido não volta à tela; o topo sim, no mesmo SSE.
        assertEquals(List.of("meta", "peca", "peca", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains("\"status\":\"completo\""), emissor.corpo());
        assertTrue(emissor.corpo().contains("\"tokensSaida\":400"), "as duas chamadas somam: " + emissor.corpo());
        List<dev.jarvis.ia.provedor.Mensagem> mensagens = anthropic.ultimo.mensagens();
        assertEquals(3, mensagens.size());
        String recebido = ((Bloco.Texto) mensagens.get(1).blocos().getFirst()).texto();
        assertEquals(META.strip() + "\n" + PECA.strip(), recebido, "a meia linha não vai de volta");
        assertEquals(PromptDeHolograma.CONTINUAR, ((Bloco.Texto) mensagens.get(2).blocos().getFirst()).texto());
    }

    @Test
    void depoisDeDuasContinuacoesOFimSaiIncompletoComOMotivo() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of(META, PECA));
        anthropic.motivo = RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.DETALHADO, emissor);
        emissor.esperar();

        assertEquals(1 + HologramaService.MAX_CONTINUACOES, anthropic.chamadas);
        assertEquals(List.of("meta", "peca", "fim"), emissor.eventos());
        assertTrue(emissor.corpo().contains("\"status\":\"incompleto\",\"motivo\":\""
                + HologramaService.MOTIVO_TETO + "\""), emissor.corpo());
        assertTrue(emissor.corpo().matches("(?s).*\"id\":\"h[0-9a-z]{8}\".*"), emissor.corpo());
    }

    @Test
    void streamQueAcabaSemFimTambemContinua() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        anthropic.porChamada = List.of(List.of(META, PECA), List.of(TOPO, FIM));
        anthropic.motivo = RespostaDoModelo.MotivoDeParada.OUTRO;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);

        servico.gerar("cubo", DetalheHolograma.NORMAL, emissor);
        emissor.esperar();

        assertEquals(2, anthropic.chamadas);
        assertTrue(emissor.corpo().contains("\"status\":\"completo\""), emissor.corpo());
    }

    @Test
    void aRetomadaReenviaOQueAHudPerdeuEContinua() throws Exception {
        Emissor emissor = new Emissor();
        Falso anthropic = new Falso(List.of());
        String outra = "{\"t\":\"p\",\"id\":\"base\",\"f\":\"box\"}\n";
        anthropic.porChamada = List.of(List.of(META, PECA, outra), List.of(), List.of(), List.of(TOPO, FIM));
        anthropic.motivo = RespostaDoModelo.MotivoDeParada.LIMITE_DE_TOKENS;
        servico = servico(anthropic, new Falso(List.of()), "anthropic", true);
        servico.gerar("cubo", DetalheHolograma.DETALHADO, emissor);
        emissor.esperar();
        assertTrue(emissor.corpo().contains("\"status\":\"incompleto\""), emissor.corpo());
        java.util.regex.Matcher id = java.util.regex.Pattern.compile("\"id\":\"(h[0-9a-z]{8})\"").matcher(emissor.corpo());
        assertTrue(id.find());

        // A HUD só tinha recebido a primeira peça.
        Emissor retomada = new Emissor();
        assertTrue(servico.continuar(id.group(1), 1, retomada));
        retomada.esperar();

        assertEquals(List.of("meta", "peca", "peca", "fim"), retomada.eventos());
        assertTrue(retomada.corpo().contains("\"id\":\"base\""), "a peça perdida volta: " + retomada.corpo());
        assertTrue(retomada.corpo().contains("\"id\":\"topo\""), retomada.corpo());
        assertFalse(retomada.corpo().contains("\"id\":\"cubo\""), "a que a HUD tem não volta");
        assertTrue(retomada.corpo().contains("\"status\":\"completo\""), retomada.corpo());
    }

    @Test
    void retomadaDeIdDesconhecidoNaoMexeNoEmissor() {
        servico = servico(new Falso(List.of()), new Falso(List.of()), "anthropic", true);
        assertFalse(servico.continuar("habcdefgh", 0, new Emissor()));
    }

    @Test
    void custoEstimadoUsaATabelaConfigurada() {
        servico = servico(new Falso(List.of()), new Falso(List.of()), "anthropic", true);
        assertEquals(0.0011, servico.custoEstimado(1_000, 2_000), 1e-9);
        assertEquals(0.0, servico.custoEstimado(0, 0), 0);
    }

    // ---- montagem ---------------------------------------------------------------------------

    private HologramaService servico(Falso anthropic, Falso local, String provedor, boolean localPode) {
        return servico(anthropic, local, provedor, localPode, Duration.ofSeconds(10), Duration.ofSeconds(10));
    }

    private HologramaService servico(Falso anthropic, Falso local, String provedor, boolean localPode,
            Duration prazo, Duration ping) {
        return servico(anthropic, local, provedor, localPode, prazo, ping, HologramaService.PRIMEIRA_RESPOSTA);
    }

    private HologramaService servico(Falso anthropic, Falso local, String provedor, boolean localPode,
            Duration prazo, Duration ping, Duration primeiraResposta) {
        JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "modelo-a", 1024, 512), null, null, null,
                null, null, null, null,
                new JarvisProperties.Pc("t", "/tmp/op.json", 1, 1, 1,
                        new JarvisProperties.Pc.IaLocal(true, "", 60, 6000)),
                null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Holograma(provedor, "modelo-holograma", 0.4));
        return new HologramaService(anthropic, local,
                () -> new DisponibilidadeLocal.Situacao(localPode, localPode ? "" : "pc_offline"),
                () -> "qwen-teste", props, new PromptDeHolograma(), new HologramaLineParser(JSON), prazo,
                ping, primeiraResposta);
    }

    /** Um provedor que transmite os pedaços dados, e reage ao corte como um stream fechado. */
    private static final class Falso implements ProvedorDeModelo {
        private final List<String> deltas;
        /** As respostas de cada chamada, na ordem (a última se repete); vazio usa {@code deltas}. */
        List<List<String>> porChamada = List.of();
        RespostaDoModelo.MotivoDeParada motivo = RespostaDoModelo.MotivoDeParada.FIM;
        final List<PedidoAoModelo> pedidos = Collections.synchronizedList(new ArrayList<>());
        IOException falha;
        RespostaDoModelo.Uso uso;
        boolean esperarCorte;
        Consumer<Integer> depoisDoDelta = i -> { };
        volatile boolean cortado;
        volatile int chamadas;
        volatile PedidoAoModelo ultimo;
        final CountDownLatch corte = new CountDownLatch(1);
        final CountDownLatch comecou = new CountDownLatch(1);

        Falso(List<String> deltas) {
            this.deltas = deltas;
        }

        @Override
        public RespostaDoModelo responder(PedidoAoModelo pedido) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RespostaDoModelo transmitir(PedidoAoModelo pedido, Consumer<String> aoTexto,
                Cancelamento cancelamento) throws IOException, InterruptedException {
            chamadas++;
            ultimo = pedido;
            pedidos.add(pedido);
            List<String> deltas = porChamada.isEmpty() ? this.deltas
                    : porChamada.get(Math.min(chamadas, porChamada.size()) - 1);
            cancelamento.aoCancelar(() -> {
                cortado = true;
                corte.countDown();
            });
            for (int i = 0; i < deltas.size(); i++) {
                if (cortado) throw new IOException("stream fechado");
                aoTexto.accept(deltas.get(i));
                depoisDoDelta.accept(i);
            }
            comecou.countDown();
            if (esperarCorte) {
                if (!corte.await(5, TimeUnit.SECONDS)) throw new IOException("ninguém cortou");
                throw new IOException("stream fechado");
            }
            if (falha != null) throw falha;
            return new RespostaDoModelo(List.of(new Bloco.Texto(String.join("", deltas))), motivo, uso);
        }
    }

    /** Um SseEmitter que guarda o que foi mandado, e que pode fingir que o cliente saiu. */
    private static final class Emissor extends SseEmitter {
        private final List<String> eventos = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder corpo = new StringBuilder();
        private final CountDownLatch completo = new CountDownLatch(1);
        volatile boolean foiEmbora;

        @Override
        public void send(SseEventBuilder evento) throws IOException {
            if (foiEmbora) throw new IOException("Broken pipe");
            StringBuilder texto = new StringBuilder();
            for (ResponseBodyEmitter.DataWithMediaType parte : evento.build()) {
                texto.append(parte.getData() instanceof String s ? s : JSON.writeValueAsString(parte.getData()));
            }
            String bruto = texto.toString();
            synchronized (corpo) {
                corpo.append(bruto);
            }
            if (bruto.startsWith("event:")) {
                eventos.add(bruto.substring(6, bruto.indexOf('\n')));
            }
        }

        @Override
        public void complete() {
            completo.countDown();
        }

        void esperar() throws InterruptedException {
            assertTrue(completo.await(5, TimeUnit.SECONDS), "a geração não terminou: " + corpo());
        }

        List<String> eventos() {
            return List.copyOf(eventos);
        }

        String corpo() {
            synchronized (corpo) {
                return corpo.toString();
            }
        }
    }
}
