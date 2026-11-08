package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.ContextoArquivo;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ContinuidadeArquivoFerramentasTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String OUTRO = "33333333-3333-3333-3333-333333333333";
    private static final String HASH = "a".repeat(64);
    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode leitura(String id) {
        return json.createObjectNode().put("id", id).put("nome", ID.equals(id) ? "nota.md" : "outro.md")
                .put("formato", "md").put("versaoEsperada", HASH).put("inicio", 0)
                .put("totalCaracteres", 5).put("conteudo", "velho");
    }

    private OuvinteProgresso contexto(ContextoArquivo alvo, String pedido) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return "pedido"; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return pedido; }
            @Override public ContextoArquivo contextoArquivo() { return alvo; }
        };
    }

    private ObjectNode edicao() {
        ObjectNode pedido = json.createObjectNode().put("destino", "original");
        pedido.putArray("operacoes").addObject().put("tipo", "substituir_trecho")
                .put("textoAtual", "velho").put("textoNovo", "novo");
        return pedido;
    }

    @Test void versaoExplicitaDivergenteEIdDeOutroArquivoNaoChegamAoAgente() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        var fluxo = mock(FluxoEdicaoPc.class);
        var editar = new EditarArquivoFerramenta(fluxo, json);
        assertTrue(editar.executarDetalhado(edicao().put("versaoEsperada", "b".repeat(64)),
                contexto(alvo, "mude esse texto")).texto().contains("diverge"));
        alvo.registrar(leitura(ID), true);
        assertTrue(editar.executarDetalhado(edicao().put("id", OUTRO).put("versaoEsperada", HASH),
                contexto(alvo, "mude esse texto")).texto().contains("outro arquivo"));
        assertNull(alvo.estado());
        assertTrue(editar.executarDetalhado(edicao(), contexto(alvo, "mude esse texto"))
                .texto().contains("Selecione"));
        verifyNoInteractions(fluxo);
    }

    @Test void nomeExplicitoDiferenteImpedeReutilizarAlvoAnterior() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        var fluxo = mock(FluxoEdicaoPc.class);
        String resultado = new EditarArquivoFerramenta(fluxo, json)
                .executarDetalhado(edicao(), contexto(alvo, "edite outro.md")).texto();
        assertTrue(resultado.contains("não use o alvo anterior"));
        assertNull(alvo.estado());
        verifyNoInteractions(fluxo);
    }

    @Test void leituraExplicitaTrocaAlvoELeituraSemIdUsaSelecaoAtual() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        var pc = mock(PcService.class);
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc c = chamada.getArgument(0);
            assertEquals(OUTRO, c.dados().path("id").asText());
            return CompletableFuture.completedFuture(new ResultadoPc(c.id(), true, "consulta concluída",
                    null, null, false, leitura(OUTRO)));
        });
        var ler = new LerArquivoFerramenta(pc, json);
        ler.executarDetalhado(json.createObjectNode().put("id", OUTRO), contexto(alvo, "leia outro.md"));
        assertEquals(OUTRO, alvo.estado().id());
        ler.executarDetalhado(json.createObjectNode(), contexto(alvo, "releia"));
        assertEquals("", alvo.erroEdicao(OUTRO, HASH));
    }

    @Test void criacaoNaoLocalizadaConservaNomeEConsultaExigeNomeExato() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.criado("nota.md");
        var pc = mock(PcService.class);
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc c = chamada.getArgument(0);
            var lista = json.createObjectNode().put("total", 1).put("temMais", false);
            lista.putArray("arquivos").add(leitura(OUTRO));
            return CompletableFuture.completedFuture(new ResultadoPc(c.id(), true, "consulta concluída",
                    null, null, false, lista));
        });
        String resultado = new LerArquivoFerramenta(pc, json)
                .executarDetalhado(json.createObjectNode(), contexto(alvo, "nesse arquivo")).texto();
        assertTrue(resultado.contains("nome exato"));
        assertEquals("nota.md", alvo.estado().nome());
        assertEquals("", alvo.estado().id());
    }

    @Test void pcOfflineNaoPerdeIdentidadeNemMantemProvaDeLeitura() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        String resultado = new LerArquivoFerramenta(mock(PcService.class), json)
                .executarDetalhado(json.createObjectNode(), contexto(alvo, "releia")).texto();
        assertTrue(resultado.contains("offline"));
        assertEquals(ID, alvo.estado().id());
        assertTrue(alvo.erroEdicao(ID, HASH).contains("Releia"));
    }

    @Test void continuidadeNaoAutorizaReescritaTotalNemSubstituicaoDeFormula() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        var fluxo = mock(FluxoEdicaoPc.class);
        var editar = new EditarArquivoFerramenta(fluxo, json);
        ObjectNode pedido = edicao();
        pedido.putArray("operacoes").addObject().put("tipo", "substituir_conteudo").put("conteudo", "novo");
        assertTrue(editar.executarDetalhado(pedido, contexto(alvo, "R$ 6")).texto().contains("pedido explícito"));
        pedido = edicao();
        ((ObjectNode) pedido.path("operacoes").get(0)).put("autorizarSubstituirFormula", true);
        assertTrue(editar.executarDetalhado(pedido, contexto(alvo, "R$ 6")).texto().contains("pedido explícito"));
        verifyNoInteractions(fluxo);
    }

    @Test void conflitoDeVersaoNaoPromoveHashNemRepetePreparacao() throws Exception {
        var alvo = new ContextoArquivo();
        alvo.registrar(leitura(ID), true);
        var fluxo = mock(FluxoEdicaoPc.class);
        when(fluxo.preparar(anyString(), any(), any())).thenReturn(new ResultadoPc(ID, false,
                "CONFLITO_DE_VERSAO", null, null, false, json.createObjectNode().put("codigoErro", "CONFLITO_DE_VERSAO")));
        var editar = new EditarArquivoFerramenta(fluxo, json);
        assertTrue(editar.executarDetalhado(edicao(), contexto(alvo, "altere o texto")).texto().contains("CONFLITO_DE_VERSAO"));
        assertEquals(HASH, alvo.estado().versao());
        assertTrue(editar.executarDetalhado(edicao(), contexto(alvo, "altere o texto")).texto().contains("Releia"));
        verify(fluxo, times(1)).preparar(anyString(), any(), any());
        verify(fluxo, never()).aplicar(anyString(), anyString(), any(), any(), anyBoolean());
    }

    /**
     * "Acha o arquivo que fala de X": listar devolve vários, e é lendo cada um que se acha o certo.
     * A ambiguidade trava a edição no pedido, não a consulta — e o que foi consultado nesse pedido
     * não vira o alvo, senão o último lido seria o "esse arquivo" da mensagem seguinte.
     */
    @Test void selecaoAmbiguaTravaEdicaoMasNaoConsultas() throws Exception {
        var alvo = new ContextoArquivo();
        var pc = mock(PcService.class);
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(), anyString(), anyLong())).thenAnswer(chamada -> {
            ComandoPc c = chamada.getArgument(0);
            if ("ler_arquivo".equals(c.acao()))
                return CompletableFuture.completedFuture(new ResultadoPc(c.id(), true, "consulta concluída",
                        null, null, false, leitura(c.dados().path("id").asText())));
            var lista = json.createObjectNode().put("total", 2).put("pagina", 1)
                    .put("tamanhoPagina", 20).put("temMais", false);
            lista.putArray("arquivos").add(leitura(ID)).add(leitura(OUTRO));
            return CompletableFuture.completedFuture(new ResultadoPc(c.id(), true, "consulta concluída",
                    null, null, false, lista));
        });
        var ouvinte = contexto(alvo, "acha o arquivo que fala de velho");
        var listar = new ListarArquivosFerramenta(pc, json);
        var ler = new LerArquivoFerramenta(pc, json);
        listar.executarDetalhado(json.createObjectNode(), ouvinte);
        assertTrue(alvo.aguardaEsclarecimento("pedido"));

        assertTrue(listar.executarDetalhado(json.createObjectNode().put("formato", "md"), ouvinte)
                .texto().contains("\"arquivos\""));
        assertTrue(ler.executarDetalhado(json.createObjectNode().put("id", ID), ouvinte)
                .texto().contains("\"conteudo\""));
        assertTrue(ler.executarDetalhado(json.createObjectNode().put("id", OUTRO), ouvinte)
                .texto().contains("\"conteudo\""));
        verify(pc, times(4)).enfileirar(any(), anyString(), anyLong());

        assertNull(alvo.estado(), "consulta num pedido ambíguo não escolhe o alvo");
        var fluxo = mock(FluxoEdicaoPc.class);
        assertTrue(new EditarArquivoFerramenta(fluxo, json).executarDetalhado(edicao().put("id", OUTRO), ouvinte)
                .texto().contains("mais de um arquivo"));
        verifyNoInteractions(fluxo);
    }
}
