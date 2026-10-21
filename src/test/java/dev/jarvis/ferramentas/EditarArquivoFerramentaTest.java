package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.FluxoEdicaoPc;
import dev.jarvis.pc.ResultadoPc;
import dev.jarvis.pc.TarefasPlanilhaService;
import tools.jackson.databind.json.JsonMapper;

class EditarArquivoFerramentaTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String EDIT = "22222222-2222-2222-2222-222222222222";
    private static final String HASH = "a".repeat(64);

    /**
     * Faz o {@code aplicar} devolver o que o lambda da ferramenta monta. É o que deixa o teste
     * conferir a frase real que chega ao modelo, e não só a chamada.
     */
    @SuppressWarnings("unchecked")
    private void aplicaDevolvendo(FluxoEdicaoPc fluxo, String nome) throws Exception {
        when(fluxo.aplicar(anyString(), anyString(), any(), any(), anyBoolean())).thenAnswer(
                chamada -> ResultadoFerramenta.texto(
                        ((UnaryOperator<String>) chamada.getArgument(3)).apply(nome)));
    }

    @Test void errosEstruturadosNaoGeramConfirmacaoNemRepeticao() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var diagnostico = json.createObjectNode().put("codigoErro", "POSICAO_DIVERGENTE");
        when(fluxo.preparar(eq("preparar_edicao"), any(), any())).thenReturn(
                new ResultadoPc(ID, false, "posição da nova linha divergente; linha esperada 10", null, null, false, diagnostico));
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.createObjectNode().put("id", ID).put("versaoEsperada", HASH).put("destino", "original");
        pedido.putArray("operacoes").addObject().put("tipo", "acrescentar_linha").put("aba", "Academias").put("linhaEsperada", 4);
        var resultado = ferramenta.executarDetalhado(pedido, contexto("novo", "adicione na tabela exemplo"));
        assertNull(resultado.confirmacao());
        assertTrue(resultado.texto().contains("POSICAO_DIVERGENTE"));
        assertTrue(resultado.texto().contains("não repita"));
        verify(fluxo, times(1)).preparar(anyString(), any(), any());
        verifyNoMoreInteractions(fluxo);
        diagnostico.put("codigoErro", "RESULTADO_INCERTO");
        assertTrue(ferramenta.executarDetalhado(pedido, contexto("outro", "adicione na tabela exemplo"))
                .texto().contains("consulte o estado"));
    }

    @Test void expansaoDeFiltroUsaPropostaImutavelDoAgente() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "tabela_exemplo.xlsx")
                .put("apelidoUnico", true).put("previa", "Academias!A4 (vazio → texto): null → academia; filtro A1:H3 → A1:H4")
                .put("expiraEm", java.time.Instant.now().plusSeconds(120).toString());
        when(fluxo.preparar(eq("preparar_edicao"), any(), any())).thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "tabela_exemplo.xlsx");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.createObjectNode().put("id", ID).put("versaoEsperada", HASH).put("destino", "original");
        var ops = pedido.putArray("operacoes");
        ops.addObject().put("tipo", "preencher_celula_vazia").put("aba", "Academias").put("celula", "A4")
                .put("tipoAnterior", "vazio").putNull("valorAnterior").put("tipoNovo", "texto").put("valorNovo", "academia");
        ops.addObject().put("tipo", "expandir_filtro").put("aba", "Academias").put("intervaloAnterior", "A1:H3").put("intervaloNovo", "A1:H4");
        var resultado = ferramenta.executarDetalhado(pedido, contexto("primeiro", "adicione academia na tabela exemplo"));
        assertNull(resultado.confirmacao());
        assertEquals("Atualizei tabela_exemplo.xlsx (2 trechos). Se quiser voltar, diga desfaz.",
                resultado.texto());
        verify(fluxo).aplicar(eq("aplicar_edicao"), eq(EDIT), any(), any(), eq(true));
        var esquema = ferramenta.esquemaEntrada().path("properties").path("operacoes").path("items");
        assertTrue(esquema.path("properties").path("tipo").path("enum").toString().contains("expandir_filtro"));
        assertTrue(esquema.path("allOf").toString().contains("intervaloAnterior"));
    }
    private OuvinteProgresso contexto(String pedidoId, String texto) {
        return contexto(pedidoId, texto, "");
    }
    private OuvinteProgresso contexto(String pedidoId, String texto, String ultimoAnexo) {
        return new OuvinteProgresso() {
            @Override public String idPedido() { return pedidoId; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return texto; }
            @Override public String ultimoAnexoDeArquivo() { return ultimoAnexo; }
        };
    }
    @Test void aplicacaoUsaEditIdDaPropostaSemRepassarOperacoes() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "nota.md")
                .put("previa", "- velho\n+ novo").put("versaoAnterior", HASH)
                .put("versaoProposta", "b".repeat(64))
                .put("expiraEm", java.time.Instant.now().plusSeconds(120).toString());
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "nota.md");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}]}");
        var resultado = ferramenta.executarDetalhado(pedido, contexto("primeiro", "acrescente x em nota.md"));
        assertNull(resultado.confirmacao());
        assertEquals("Atualizei nota.md (1 trecho). Se quiser voltar, diga desfaz.", resultado.texto());
        assertFalse(resultado.texto().contains(EDIT));
        assertFalse(resultado.texto().contains(HASH));
        // Só o editId atravessa: o comando de aplicação não leva operação nem conteúdo.
        verify(fluxo).aplicar(eq("aplicar_edicao"), eq(EDIT), any(), any(), eq(true));
    }

    @Test void esseArquivoUsaSomenteOAnexoAnteriorCorrespondente() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "relatorio.md")
                .put("previa", "- Chat GPT\n+ você")
                .put("expiraEm", java.time.Instant.now().plusSeconds(120).toString())
                .put("ocorrenciasSubstituidas", 3);
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "relatorio.md");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"substituir_todas_ocorrencias\",\"textoAtual\":\"Chat GPT\",\"textoNovo\":\"você\",\"ignorarMaiusculas\":true}]}");

        var aceito = ferramenta.executarDetalhado(pedido,
                contexto("pedido-1", "edite esse arquivo onde tiver Chat GPT", "relatorio.md"));
        // A contagem vem das ocorrências que o agente substituiu, não do número de operações.
        assertEquals("Atualizei relatorio.md (3 trechos). Se quiser voltar, diga desfaz.",
                aceito.texto());
        assertFalse(aceito.texto().contains(EDIT));
        assertFalse(aceito.texto().contains(HASH));

        var recusado = ferramenta.executarDetalhado(pedido,
                contexto("pedido-2", "edite esse arquivo", "outro.md"));
        assertTrue(recusado.texto().contains("não corresponde"));
    }

    @Test void nomeSemelhanteNaoAutorizaOutroArquivo() {
        assertFalse(EditarArquivoFerramenta.arquivoConstaDoPedido(
                "edite meu relatório de assistentes", "relatorio_de_assistentes.md", ID, ""));
        assertFalse(EditarArquivoFerramenta.arquivoConstaDoPedido(
                "edite outra-nota.md", "nota.md", ID, ""));
        assertTrue(EditarArquivoFerramenta.arquivoConstaDoPedido(
                "edite NOTA.md", "nota.md", ID, ""));
        assertFalse(EditarArquivoFerramenta.arquivoConstaDoPedido(
                "edite outro.md, não esse arquivo", "nota.md", ID, "nota.md"));
    }

    @Test void nomeFaladoDaPlanilhaAutorizaSomenteApelidoUnico() throws Exception {
        assertTrue(EditarArquivoFerramenta.apelidoConstaDoPedido(
                "Marque Oficina Fictícia como paga na tabela exemplo", "tabela_exemplo.xlsx"));
        assertFalse(EditarArquivoFerramenta.apelidoConstaDoPedido(
                "Marque Oficina Fictícia como paga na tabela vendas", "tabela_exemplo.xlsx"));
        assertFalse(EditarArquivoFerramenta.apelidoConstaDoPedido(
                "Edite outra.xlsx e a tabela exemplo", "tabela_exemplo.xlsx"));
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT)
                .put("nome", "tabela_exemplo.xlsx")
                .put("previa", "Clientes!E3 (booleano → booleano): false → true")
                .put("expiraEm", java.time.Instant.now().plusSeconds(120).toString())
                .put("apelidoUnico", true);
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "tabela_exemplo.xlsx");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"atualizar_celula\","
                + "\"aba\":\"Clientes\",\"celula\":\"E3\",\"tipoAnterior\":\"booleano\","
                + "\"valorAnterior\":false,\"tipoNovo\":\"booleano\",\"valorNovo\":true}]}");
        var contexto = contexto("pedido-1", "Marque Oficina Fictícia como paga na tabela exemplo");
        assertTrue(ferramenta.executarDetalhado(pedido, contexto).texto()
                .startsWith("Atualizei tabela_exemplo.xlsx"));
        dados.put("apelidoUnico", false);
        var ambiguo = ferramenta.executarDetalhado(pedido,
                contexto("pedido-2", "Marque Oficina Fictícia como paga na tabela exemplo"));
        assertTrue(ambiguo.texto().contains("Mais de um arquivo"), ambiguo.texto());
        verify(fluxo, times(1)).aplicar(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void nomeFaladoValeParaMarkdownENaoSoParaPlanilha() throws Exception {
        assertTrue(EditarArquivoFerramenta.apelidoConstaDoPedido(
                "tira os dados da Apple do assistentes ia mais usados 2026",
                "assistentes_ia_mais_usados_2026.md"));
        assertFalse(EditarArquivoFerramenta.apelidoConstaDoPedido(
                "tira os dados da Apple da lista de assistentes",
                "assistentes_ia_mais_usados_2026.md"));
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT)
                .put("nome", "assistentes_ia_mais_usados_2026.md")
                .put("previa", "- | Apple | 4 |").put("apelidoUnico", true)
                .put("ocorrenciasSubstituidas", 2);
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "assistentes_ia_mais_usados_2026.md");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":"
                + "\"substituir_todas_ocorrencias\",\"textoAtual\":\"| Apple | 4 |\\n\",\"textoNovo\":\"\"}]}");
        assertEquals("Atualizei assistentes_ia_mais_usados_2026.md (2 trechos)."
                + " Se quiser voltar, diga desfaz.",
                ferramenta.executarDetalhado(pedido, contexto("pedido-1",
                        "tira os dados da Apple do assistentes ia mais usados 2026")).texto());
        // Apelido repetido em outro arquivo continua sendo recusado.
        dados.put("apelidoUnico", false);
        var ambiguo = ferramenta.executarDetalhado(pedido, contexto("pedido-2",
                "tira os dados da Apple do assistentes ia mais usados 2026"));
        assertTrue(ambiguo.texto().contains("Mais de um arquivo"), ambiguo.texto());
    }

    @Test void copiaNaoSolicitadaNemChegaAoAgente() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"copia\",\"operacoes\":[{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}]}");
        var resultado = ferramenta.executarDetalhado(pedido,
                contexto("pedido", "edite esse arquivo", "nota.md"));
        assertTrue(resultado.texto().contains("não autorizou criar uma cópia"));
        verify(fluxo, never()).preparar(anyString(), any(), any());
    }

    @Test void documentoNaoAutorizaEditarOutroArquivo() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "segredo.md")
                .put("previa", "- a\n+ b").put("versaoAnterior", HASH)
                .put("versaoProposta", "b".repeat(64));
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"acrescentar_ao_final\",\"texto\":\"x\"}]}");
        var resultado = ferramenta.executarDetalhado(pedido, contexto("primeiro", "acrescente x em nota.md"));
        assertTrue(resultado.texto().contains("não corresponde"));
        verify(fluxo, never()).aplicar(anyString(), anyString(), any(), any(), anyBoolean());
    }

    @Test void tarefaAutorizadaAplicaDireto() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        TarefasPlanilhaService tarefas = mock(TarefasPlanilhaService.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "dados.xlsx")
                .put("previa", "Dados!A2 (numero → numero): 5 → 7");
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        when(tarefas.autorizada("tarefa-1", 42, ID, "dados.xlsx", false)).thenReturn(true);
        aplicaDevolvendo(fluxo, "dados.xlsx");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json, tarefas);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"original\",\"operacoes\":[{\"tipo\":\"atualizar_celula\","
                + "\"aba\":\"Dados\",\"celula\":\"A2\",\"tipoAnterior\":\"numero\","
                + "\"valorAnterior\":5,\"tipoNovo\":\"numero\",\"valorNovo\":7}]}");
        OuvinteProgresso contexto = new OuvinteProgresso() {
            @Override public String idPedido() { return "execucao-1"; }
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String pedidoOriginal() { return "atualize dados.xlsx"; }
            @Override public String canal() { return "tarefa"; }
            @Override public String idTarefaPlanilha() { return "tarefa-1"; }
        };
        assertNull(ferramenta.executarDetalhado(pedido, contexto).confirmacao());
        verify(fluxo).aplicar(eq("aplicar_edicao"), eq(EDIT), eq(contexto), any(), eq(true));
    }

    @Test void copiaSimplificadaCriaArquivoNovoSemAprovacao() throws Exception {
        FluxoEdicaoPc fluxo = mock(FluxoEdicaoPc.class);
        var dados = json.createObjectNode().put("editId", EDIT).put("nome", "dados.xlsx")
                .put("previa", "Cópia simplificada: 1 gráfico será omitido")
                .put("expiraEm", java.time.Instant.now().plusSeconds(120).toString());
        when(fluxo.preparar(eq("preparar_edicao"), any(), any()))
                .thenReturn(new ResultadoPc(ID, true, "ok", null, null, false, dados));
        aplicaDevolvendo(fluxo, "dados-simplificado.xlsx");
        var ferramenta = new EditarArquivoFerramenta(fluxo, json);
        var pedido = json.readTree("{\"id\":\"" + ID + "\",\"versaoEsperada\":\"" + HASH
                + "\",\"destino\":\"copia\",\"simplificar\":true,\"operacoes\":[{\"tipo\":\"inserir_linha\","
                + "\"aba\":\"Dados\",\"linha\":2}]}");
        var resultado = ferramenta.executarDetalhado(pedido,
                contexto("pedido-1", "insira uma linha em dados.xlsx"));
        assertNull(resultado.confirmacao());
        // A cópia não se desfaz, então a prévia continua indo ao modelo — e ela não entra em
        // ultimasEdicoes, o que é o `false` do último argumento.
        assertTrue(resultado.texto().contains("1 gráfico será omitido"), resultado.texto());
        assertTrue(resultado.texto().endsWith("Cópia criada: dados-simplificado.xlsx."),
                resultado.texto());
        verify(fluxo).aplicar(eq("aplicar_edicao"), eq(EDIT), any(), any(), eq(false));
    }
}
