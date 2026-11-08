package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.*;
import dev.jarvis.pc.*;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class RegistrosReversiveisPcTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final String arquivoId = "11111111-1111-1111-1111-111111111111";
    final String editId = "22222222-2222-2222-2222-222222222222";
    final OuvinteProgresso contexto = new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
        @Override public String idPedido() { return "pedido"; }
        @Override public String canal() { return "hud"; }
    };
    JarvisProperties props() {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("UTC"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("teste", 0)); return props;
    }
    @Test void criacaoConfirmadaRegistraIdentidadeDoCatalogoMesmoSemAnexoOuContextoDeArquivo() throws Exception {
        var props = props(); var diario = new DiarioDeAcoes(props, json, new RelogioFalso(), pasta.resolve("diario"));
        var pc = mock(PcService.class); when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), anyString(), anyLong())).thenReturn(CompletableFuture.completedFuture(
                new ResultadoPc("comando", true, "nome-real-1.md", null, null, false)));
        var lixeira = mock(LixeiraPcService.class); when(lixeira.idPorNome("nome-real-1.md", contexto)).thenReturn(Optional.of(arquivoId));
        var ferramenta = new CriarArquivoFerramenta(pc, mock(ArquivosPcService.class), mock(TelegramClient.class), json, props, diario);
        ferramenta.configurarLixeira(lixeira);
        try {
            ferramenta.executarDetalhado(json.createObjectNode().put("nome", "nome-pedido").put("formato", "md").put("conteudo", "Olá."), contexto);
            var acao = diario.deHoje().acoes().getFirst();
            assertEquals(new Desfazer.Arquivo(arquivoId), acao.desfazer());
            assertEquals("criei nome-real-1.md", acao.descricao()); assertEquals(42, acao.usuario());
            verify(lixeira).idPorNome("nome-real-1.md", contexto);
        } finally { diario.fechar(); }
    }
    @Test void edicaoECopiaRegistramCapacidadesDiferentesMesmoSemEntrega() throws Exception {
        var diario = new DiarioDeAcoes(props(), json, new RelogioFalso(), pasta.resolve("diario"));
        var operacoes = mock(OperacoesPcService.class); when(operacoes.online()).thenReturn(true);
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(c ->
                CompletableFuture.completedFuture(new ResultadoPc(((ComandoPc)c.getArgument(0)).id(), true,
                        "nota.md", null, null, false, json.createObjectNode().put("id", arquivoId))));
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class), mock(TelegramClient.class), json, diario);
        try {
            fluxo.aplicar("aplicar_edicao", editId, contexto, n -> "Atualizei " + n, true);
            fluxo.aplicar("aplicar_edicao", "33333333-3333-3333-3333-333333333333", contexto, n -> "Criei cópia " + n, false);
            assertEquals(new Desfazer.Edicao(editId), diario.deHoje().acoes().get(0).desfazer());
            assertEquals(new Desfazer.Arquivo(arquivoId), diario.deHoje().acoes().get(1).desfazer());
        } finally { diario.fechar(); }
    }

    /** O "lê aquele arquivo de novo" do retrato do momento depende do ID do catálogo estar na linha. */
    @Test void edicaoEDesfazerGuardamOIdDoCatalogoNaReferencia() throws Exception {
        var diario = new DiarioDeAcoes(props(), json, new RelogioFalso(), pasta.resolve("diario"));
        var selecionado = new dev.jarvis.conversa.ContextoArquivo();
        assertTrue(selecionado.registrar(json.createObjectNode().put("id", arquivoId).put("nome", "nota.md")
                .put("formato", "md").put("versaoEsperada", "a".repeat(64)), true));
        var comAlvo = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido"; }
            @Override public String canal() { return "hud"; }
            @Override public dev.jarvis.conversa.ContextoArquivo contextoArquivo() { return selecionado; }
        };
        var operacoes = mock(OperacoesPcService.class); when(operacoes.online()).thenReturn(true);
        // O agente não devolve o id no resultado: quem diz qual arquivo foi é o alvo validado.
        when(operacoes.enfileirar(any(), any(), any(), anyString(), anyLong())).thenAnswer(c ->
                CompletableFuture.completedFuture(new ResultadoPc(((ComandoPc)c.getArgument(0)).id(), true,
                        "nota.md", null, null, false, json.createObjectNode().put("versao", "b".repeat(64)))));
        var fluxo = new FluxoEdicaoPc(operacoes, mock(ArquivosPcService.class), mock(TelegramClient.class), json, diario);
        try {
            fluxo.aplicar("aplicar_edicao", editId, comAlvo, n -> "Atualizei " + n, true);
            // O desfazer acha o arquivo pela edição que desfaz, mesmo sem alvo selecionado.
            fluxo.aplicar("aplicar_desfazer", editId, contexto, n -> "Voltei " + n, true);
            var acoes = diario.deHoje().acoes();
            assertEquals(TipoDeAcao.EDICAO_ARQUIVO, acoes.get(0).tipo());
            assertEquals(arquivoId, acoes.get(0).referencia());
            assertEquals(TipoDeAcao.DESFAZER_EDICAO, acoes.get(1).tipo());
            assertEquals(arquivoId, acoes.get(1).referencia());
        } finally { diario.fechar(); }
    }
}
