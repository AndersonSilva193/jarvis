package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.Origem;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.ia.provedor.SeletorDeModelo.Provedor;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;

/** Os três modos com o local pronto e fora, o aviso único, o disco e o evento. */
class SeletorDeModeloTest {

    private static final String FORA = BancadaLocal.PRONTA.replace("ocupada=0", "ocupada=1");
    private static final String AVISO = "O PC não está disponível; estou usando o Claude por enquanto.";

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final List<Object> eventos = new CopyOnWriteArrayList<>();
    private final AvisosProativos avisos = mock(AvisosProativos.class);
    private DiarioDeAcoes diario;

    @BeforeEach
    void montar() {
        bancada = new BancadaLocal(pasta);
        diario = new DiarioDeAcoes(bancada.props, BancadaLocal.JSON, Clock.systemUTC(),
                pasta.resolve("diario"));
    }

    @AfterEach
    void fechar() throws Exception {
        bancada.close();
        diario.fechar();
    }

    private SeletorDeModelo seletor() {
        return seletor(bancada.props);
    }

    private SeletorDeModelo seletor(JarvisProperties props) {
        return new SeletorDeModelo(bancada.disponibilidade, props, BancadaLocal.JSON, eventos::add,
                avisos, new FalasService(), diario, Clock.systemUTC(), pasta.resolve("modo-ia.json"));
    }

    /** As properties da bancada com outra IA local: chave mestra e modo padrão. */
    private JarvisProperties props(boolean ativa, String modoPadrao) {
        JarvisProperties.Pc pc = bancada.props.pc();
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 0L),
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                null, null, null, null, null, null, null,
                new JarvisProperties.Pc("token", pc.operacoesPasta(), 1024 * 1024, 10, 1024 * 1024,
                        new JarvisProperties.Pc.IaLocal(ativa, "", 1, 6000, modoPadrao)),
                null, null, null, null, null, bancada.props.custo());
    }

    private List<ModoDeIaEvento> publicados() {
        return eventos.stream().filter(ModoDeIaEvento.class::isInstance)
                .map(ModoDeIaEvento.class::cast).toList();
    }

    // ---------------------------------------------------------------- os três modos

    @Test
    @DisplayName("AUTO: o local quando o PC pode, o Claude com o motivo quando não — sem aviso")
    void auto() {
        SeletorDeModelo seletor = seletor();
        assertEquals(ModoDeIa.AUTO, seletor.modo());
        assertEquals(new SeletorDeModelo.Escolha(Provedor.LOCAL, ""), seletor.escolher(Origem.BRIEFING));

        bancada.cabecalho(FORA);
        SeletorDeModelo.Escolha fora = seletor.escolher(Origem.BRIEFING);
        assertEquals(Provedor.CLAUDE, fora.provedor());
        assertEquals(DisponibilidadeLocal.GPU_OCUPADA, fora.motivo());
        assertTrue(fora.indisponivel());
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("LOCAL: o local quando o PC pode; fora, o Claude assim mesmo")
    void local() {
        SeletorDeModelo seletor = seletor();
        seletor.mudar(ModoDeIa.LOCAL, "telegram");
        assertTrue(seletor.escolher(Origem.ANOTADOR).local());

        bancada.cabecalho(BancadaLocal.PRONTA.replace("ativo=1", "ativo=0"));
        SeletorDeModelo.Escolha fora = seletor.escolher(Origem.ANOTADOR);
        assertEquals(Provedor.CLAUDE, fora.provedor());
        assertEquals(DisponibilidadeLocal.OLLAMA_OFFLINE, fora.motivo());
    }

    @Test
    @DisplayName("CLAUDE: sempre o Claude, sem motivo, com o local pronto ou fora")
    void claude() {
        SeletorDeModelo seletor = seletor();
        seletor.mudar(ModoDeIa.CLAUDE, "hud");
        assertEquals(new SeletorDeModelo.Escolha(Provedor.CLAUDE, ""), seletor.escolher(Origem.CLASSIFICADOR));
        bancada.cabecalho(FORA);
        SeletorDeModelo.Escolha fora = seletor.escolher(Origem.CLASSIFICADOR);
        assertEquals(new SeletorDeModelo.Escolha(Provedor.CLAUDE, ""), fora);
        assertFalse(fora.indisponivel(), "não conta reserva: ninguém tentou o local");
    }

    @Test
    @DisplayName("chave mestra desligada: Claude em qualquer modo, e no LOCAL nem avisa")
    void chaveMestraDesligada() {
        SeletorDeModelo seletor = seletor(props(false, "local"));
        assertEquals(ModoDeIa.LOCAL, seletor.modo());
        SeletorDeModelo.Escolha escolha = seletor.escolher(Origem.BRIEFING);
        assertEquals(new SeletorDeModelo.Escolha(Provedor.CLAUDE, SeletorDeModelo.IA_LOCAL_DESLIGADA), escolha);
        assertFalse(escolha.indisponivel());
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("computer use e pedido pontual vão ao Claude; o escopo pontual fecha")
    void pontualEComputerUse() {
        SeletorDeModelo seletor = seletor();
        assertEquals(SeletorDeModelo.SO_ANTHROPIC, seletor.escolher(Origem.COMPUTER_USE).motivo());
        try (SeletorDeModelo.Escopo escopo = seletor.claudeNestePedido()) {
            assertEquals(new SeletorDeModelo.Escolha(Provedor.CLAUDE, SeletorDeModelo.PONTUAL),
                    seletor.escolher(Origem.CLASSIFICADOR));
        }
        assertTrue(seletor.escolher(Origem.CLASSIFICADOR).local(), "fora do escopo, o modo volta a valer");
        assertEquals(ModoDeIa.AUTO, seletor.modo(), "o pontual não mexe no modo");
    }

    // ---------------------------------------------------------------- aviso único

    @Test
    @DisplayName("LOCAL forçado: um aviso por período de indisponibilidade, não por chamada")
    void avisoUnicoPorPeriodo() {
        SeletorDeModelo seletor = seletor();
        seletor.mudar(ModoDeIa.LOCAL, "telegram");
        bancada.cabecalho(FORA);
        seletor.escolher(Origem.BRIEFING);
        seletor.escolher(Origem.ANOTADOR);
        seletor.escolher(Origem.CLASSIFICADOR);
        verify(avisos, times(1)).avisar(eq(SeletorDeModelo.CHAVE_DO_AVISO),
                startsWith("indisponivel:"), eq(AVISO), isNull());

        // O PC volta, e cai de novo: período novo, aviso novo.
        bancada.cabecalho(BancadaLocal.PRONTA);
        seletor.reavaliar();
        bancada.cabecalho(FORA);
        seletor.escolher(Origem.BRIEFING);
        seletor.escolher(Origem.BRIEFING);
        verify(avisos, times(2)).avisar(eq(SeletorDeModelo.CHAVE_DO_AVISO),
                startsWith("indisponivel:"), eq(AVISO), isNull());
    }

    @Test
    @DisplayName("o tique só reavalia: sem chamada que caiu no Claude, não há aviso")
    void tiqueNaoAvisa() {
        SeletorDeModelo seletor = seletor();
        seletor.mudar(ModoDeIa.LOCAL, "telegram");
        bancada.cabecalho(FORA);
        seletor.reavaliar();
        seletor.retrato();
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("quem pede LOCAL com o PC fora já ouviu na resposta: o período não avisa de novo")
    void trocarParaLocalComPcForaContaComoAviso() {
        SeletorDeModelo seletor = seletor();
        bancada.cabecalho(FORA);
        seletor.mudar(ModoDeIa.LOCAL, "telegram");
        seletor.escolher(Origem.BRIEFING);
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), isNull());
    }

    // ---------------------------------------------------------------- disco

    @Test
    @DisplayName("o modo sobrevive ao restart; sem arquivo vale o IA_MODO_PADRAO")
    void sobreviveAoRestart() throws Exception {
        assertEquals(ModoDeIa.CLAUDE, seletor(props(true, "Claude")).modo(), "padrão sem arquivo");

        seletor().mudar(ModoDeIa.LOCAL, "telegram");
        assertTrue(Files.readString(pasta.resolve("modo-ia.json")).contains("\"modo\":\"local\""));

        assertEquals(ModoDeIa.LOCAL, seletor(props(true, "claude")).modo(),
                "o arquivo vence o padrão depois da primeira troca");
    }

    @Test
    @DisplayName("arquivo torto e padrão torto caem no AUTO, sem derrubar nada")
    void arquivoTorto() throws Exception {
        Files.writeString(pasta.resolve("modo-ia.json"), "{\"modo\":\"turbo\"}");
        assertEquals(ModoDeIa.AUTO, seletor().modo());
        Files.writeString(pasta.resolve("modo-ia.json"), "não é json");
        assertEquals(ModoDeIa.CLAUDE, seletor(props(true, "claude")).modo());
        Files.delete(pasta.resolve("modo-ia.json"));
        assertEquals(ModoDeIa.AUTO, seletor(props(true, "médio")).modo());
    }

    // ---------------------------------------------------------------- evento e diário

    @Test
    @DisplayName("troca de modo publica e anota no diário; o mesmo modo não faz nada")
    void trocaPublicaEAnota() throws Exception {
        SeletorDeModelo seletor = seletor();
        assertTrue(seletor.mudar(ModoDeIa.LOCAL, "telegram"));
        assertFalse(seletor.mudar(ModoDeIa.LOCAL, "telegram"));

        assertEquals(List.of(new ModoDeIaEvento("local", "local", "qwen3.5:9b", "")), publicados());
        diario.aguardar();
        var acoes = diario.deHoje().acoes();
        assertEquals(1, acoes.size());
        assertEquals(TipoDeAcao.TROCA_DE_MODELO, acoes.getFirst().tipo());
        assertEquals("Modo de IA: automático → local.", acoes.getFirst().descricao());
    }

    @Test
    @DisplayName("entrada e saída de fallback publicam uma vez cada, com o motivo e o modelo")
    void fallbackPublica() {
        SeletorDeModelo seletor = seletor(props(true, "auto"));
        seletor.escolher(Origem.BRIEFING); // a primeira avaliação também é uma mudança
        eventos.clear();

        bancada.cabecalho(FORA);
        seletor.escolher(Origem.BRIEFING);
        seletor.escolher(Origem.ANOTADOR);
        seletor.reavaliar();
        assertEquals(List.of(new ModoDeIaEvento("auto", "claude", "claude-haiku-5-5",
                DisponibilidadeLocal.GPU_OCUPADA)), publicados());

        bancada.cabecalho(BancadaLocal.PRONTA);
        seletor.reavaliar();
        seletor.escolher(Origem.BRIEFING);
        assertEquals(2, publicados().size());
        assertEquals(new ModoDeIaEvento("auto", "local", "qwen3.5:9b", ""), publicados().getLast());
    }
}
