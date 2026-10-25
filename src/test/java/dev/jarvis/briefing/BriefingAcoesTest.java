package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ApplicationEventPublisher;

import dev.jarvis.briefing.BriefingService.Secao;
import dev.jarvis.briefing.fontes.ClimaService;
import dev.jarvis.briefing.fontes.NoticiasService;
import dev.jarvis.briefing.fontes.ServidorService;
import dev.jarvis.briefing.fontes.SitesService;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.lembretes.LembreteService;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/**
 * A seção "o que fiz sozinho": aparece com a contagem, desaparece quando não houve nada.
 *
 * As outras fontes são mocks que devolvem null e caem no try/catch do {@code adicionar} — aqui só
 * interessa se a seção do diário entra na lista e com que texto.
 */
class BriefingAcoesTest {

    @TempDir
    Path pasta;

    private final List<DiarioDeAcoes> criados = new ArrayList<>();

    @AfterEach
    void fechar() {
        criados.forEach(DiarioDeAcoes::fechar);
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
    }

    private DiarioDeAcoes diario(RelogioFalso relogio) {
        var novo = new DiarioDeAcoes(props(), JsonMapper.builder().build(), relogio, pasta);
        criados.add(novo);
        return novo;
    }

    /** Briefing com as quatro fontes mudas e o diário de verdade. */
    private static BriefingService briefing(DiarioDeAcoes diario) {
        var lembretes = mock(LembreteService.class);
        when(lembretes.listar()).thenReturn(List.of());
        var sites = mock(SitesService.class);
        when(sites.checarTodos()).thenReturn(List.of());
        var narrador = mock(NarradorDeBriefing.class);
        // Sem narração: fica o texto montado em Java, que é o que este teste afirma.
        when(narrador.narrar(anyString(), any())).thenReturn(List.of());
        return new BriefingService(mock(ClimaService.class), mock(NoticiasService.class), sites,
                mock(ServidorService.class), lembretes, narrador, props(),
                mock(ApplicationEventPublisher.class), diario);
    }

    private static Optional<Secao> secaoDeAcoes(BriefingService.Briefing briefing) {
        return briefing.secoes().stream()
                .filter(s -> "o que fiz sozinho".equals(s.titulo()))
                .findFirst();
    }

    @Test
    @DisplayName("com ação autônoma a seção aparece, contando por tipo")
    void aSecaoApareceComAContagem() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei outra.md", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei terceira.md", OrigemDaAcao.HUD);
        diario.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei \"stack\"", OrigemDaAcao.ANOTADOR);
        diario.aguardar();

        var secao = secaoDeAcoes(briefing(diario).gerar()).orElseThrow();

        assertEquals("3 edições de arquivo, 1 anotação na memória.", secao.conteudo(),
                "a contagem vem do mais frequente para o menos, com o rótulo no número certo");
        assertFalse(secao.conteudo().contains("nota.md"),
                "a seção conta, não lista: o detalhe é da ferramenta acoes_do_jarvis");
    }

    @Test
    @DisplayName("sem ação nenhuma a seção não aparece")
    void semAcaoASecaoNaoAparece() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));

        var briefing = briefing(diario).gerar();

        assertTrue(secaoDeAcoes(briefing).isEmpty(),
                "um briefing que diz 'não fiz nada' todo dia treina o dono a ignorar a seção");
        assertFalse(briefing.paraTexto().contains("O QUE FIZ SOZINHO"), briefing.paraTexto());
    }

    @Test
    @DisplayName("ação aprovada sozinha não cria a seção: o dono já sabe que autorizou")
    void acaoAprovadaNaoCriaASecao() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));
        diario.registrar(TipoDeAcao.DESLIGAR_PC, "desligar o PC", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.ROTINA, "Executar rotina backup", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        assertTrue(secaoDeAcoes(briefing(diario).gerar()).isEmpty(),
                "ele respondeu 'Posso?' dois minutos antes; repetir isso no briefing é ruído");
    }

    @Test
    @DisplayName("o segundo briefing do dia não repete o que o primeiro já contou")
    void oSegundoBriefingNaoRepete() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();
        var servico = briefing(diario);

        assertTrue(secaoDeAcoes(servico.gerar()).isPresent(), "o primeiro conta");
        diario.aguardar();

        assertTrue(secaoDeAcoes(servico.gerar()).isEmpty(),
                "'desde o último briefing' tem de significar o que diz");
    }

    @Test
    @DisplayName("a seção omitida não desalinha as outras no texto montado em Java")
    void aSecaoOmitidaNaoDesalinha() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);

        var semAcoes = briefing(diario).gerar();
        assertEquals(4, semAcoes.secoes().size(),
                "clima, lembretes, sistemas e notícias — a quinta só entra quando tem conteúdo");

        // O relógio tem de andar: aquele gerar() avançou o marcador até o instante atual, e a
        // janela do briefing seguinte é o que vem estritamente depois dele.
        relogio.avancar(Duration.ofMinutes(1));
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();
        var comAcoes = briefing(diario).gerar();

        assertEquals(5, comAcoes.secoes().size());
        assertEquals("sistemas", comAcoes.secoes().get(2).titulo());
        assertEquals("o que fiz sozinho", comAcoes.secoes().get(3).titulo(),
                "entra depois de sistemas: é informação da casa, e notícia fecha o briefing");
        assertEquals("notícias", comAcoes.secoes().get(4).titulo());
    }

    @Test
    @DisplayName("os dados estruturados da seção levam o rótulo falado e a quantidade")
    void osDadosVaoParaAHud() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));
        diario.registrar(TipoDeAcao.RESTAURAR_ARQUIVO, "restaurei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        var secao = secaoDeAcoes(briefing(diario).gerar()).orElseThrow();

        assertTrue(secao.dados() instanceof DadosSecao.Acoes, "a HUD precisa da forma estruturada");
        var itens = ((DadosSecao.Acoes) secao.dados()).itens();
        assertEquals(1, itens.size());
        assertEquals("arquivo restaurado", itens.getFirst().tipo(),
                "o rótulo é o falado, no singular: um item, não 'arquivos restaurados'");
        assertEquals(1, itens.getFirst().quantidade());
    }
}
