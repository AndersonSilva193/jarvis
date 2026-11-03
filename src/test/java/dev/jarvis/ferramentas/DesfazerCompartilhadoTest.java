package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.*;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.memoria.*;
import dev.jarvis.pc.*;
import tools.jackson.databind.json.JsonMapper;

class DesfazerCompartilhadoTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final OuvinteProgresso contexto = new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
        @Override public String idPedido() { return "pedido"; }
        @Override public String pedidoOriginal() { return "desfaz isso"; }
        @Override public String canal() { return "hud"; }
    };
    @Test void aliasDeEdicaoEAnotacaoCompartilhamReservaComDesfazerGeral() throws Exception {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("UTC"));
        var relogio = new RelogioFalso(); var diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        var memoria = new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json, relogio, props.zona());
        var fluxo = mock(FluxoEdicaoPc.class); var lixeira = mock(LixeiraPcService.class);
        when(lixeira.online()).thenReturn(true);
        when(fluxo.preparar(anyString(), any(), any())).thenReturn(new ResultadoPc("preparo", true, "ok", null, null, false, json.createObjectNode().put("editId", "11111111-1111-1111-1111-111111111111")));
        when(lixeira.executar(anyString(), any(), any(), anyString())).thenReturn(new ResultadoPc("comando", true, "nota.md", null, null));
        var desfazedor = new DesfazedorDeAcoes(diario, fluxo, lixeira, mock(OperacoesPcService.class), memoria,
                new ProcedimentosService(pasta.resolve("procedimentos"), json, relogio), mock(SeletorDeModelo.class), mock(TarefasPlanilhaService.class), json);
        try {
            String editId = "11111111-1111-1111-1111-111111111111";
            String edicao = diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.HUD, 42, new Desfazer.Edicao(editId));
            var aliasEdicao = new DesfazerEdicaoFerramenta(fluxo, json); aliasEdicao.configurarDesfazedor(desfazedor);
            assertTrue(aliasEdicao.executarDetalhado(json.createObjectNode().put("editId", editId), contexto).texto().contains("versão anterior"));
            assertTrue(desfazedor.desfazer(edicao, contexto).texto().contains("já foi desfeita"));
            verify(lixeira, times(1)).executar(anyString(), any(), any(), anyString());
            var anotada = memoria.anotar(42, "perfil", "", "cidade", "Curitiba", "anotador:hud", "anotador-1");
            String anotacao = diario.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei cidade", OrigemDaAcao.ANOTADOR, 42, new Desfazer.Anotacao(List.of(anotada.id())));
            var anotador = mock(AnotadorDeMemoria.class);
            when(anotador.consumirDesfazivel(42)).thenReturn(Optional.of(new AnotadorDeMemoria.Anotado(
                    List.of(anotada.id()), List.of(anotada.conteudo()), relogio.instant(), List.of(anotacao))));
            desfazedor.desfazer(anotacao, contexto);
            var aliasAnotacao = new DesfazerAnotacaoFerramenta(memoria, json, anotador); aliasAnotacao.configurarDesfazedor(desfazedor);
            assertTrue(aliasAnotacao.executarDetalhado(json.createObjectNode(), contexto).texto().contains("já foi desfeita"));
            assertNull(memoria.ler(42, anotada.id()));
        } finally { diario.fechar(); }
    }

    @Test void pedidoPrecisaSerUmaSolicitacaoEIdsNaoPodemSerInventados() throws Exception {
        assertTrue(DesfazerAcaoFerramenta.pedidoExplicito("Jarvis, desfaz isso"));
        assertTrue(DesfazerAcaoFerramenta.pedidoExplicito("pode desfazer a edição?"));
        assertTrue(DesfazerAcaoFerramenta.pedidoExplicito("volta o arquivo que você apagou"));
        assertFalse(DesfazerAcaoFerramenta.pedidoExplicito("não quero desfazer a edição"));
        assertFalse(DesfazerAcaoFerramenta.pedidoExplicito("como funciona desfazer?"));
        assertFalse(DesfazerAcaoFerramenta.pedidoExplicito("bom dia"));
        var servico = mock(DesfazedorDeAcoes.class);
        var ferramenta = new DesfazerAcaoFerramenta(servico, json);
        ferramenta.executarDetalhado(json.createObjectNode(), new OuvinteProgresso() {
            @Override public String pedidoOriginal() { return "me explica como desfazer"; }
        });
        verifyNoInteractions(servico);
    }
}
