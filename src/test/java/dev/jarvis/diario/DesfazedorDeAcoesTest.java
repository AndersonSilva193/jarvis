package dev.jarvis.diario;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.ia.provedor.ModoDeIa;
import dev.jarvis.ia.provedor.SeletorDeModelo;
import dev.jarvis.memoria.MemoriaService;
import dev.jarvis.memoria.ProcedimentosService;
import dev.jarvis.pc.*;
import tools.jackson.databind.json.JsonMapper;

class DesfazedorDeAcoesTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final RelogioFalso relogio = new RelogioFalso("2026-11-03T12:00:00Z");
    final JarvisProperties props = mock(JarvisProperties.class);
    final FluxoEdicaoPc edicoes = mock(FluxoEdicaoPc.class);
    final LixeiraPcService lixeira = mock(LixeiraPcService.class);
    final OperacoesPcService operacoes = mock(OperacoesPcService.class);
    final SeletorDeModelo modelos = mock(SeletorDeModelo.class);
    final TarefasPlanilhaService tarefas = mock(TarefasPlanilhaService.class);
    DiarioDeAcoes diario;
    MemoriaService memoria;
    ProcedimentosService procedimentos;
    DesfazedorDeAcoes desfazedor;
    final OuvinteProgresso contexto = contexto(42);
    static OuvinteProgresso contexto(long usuario) { return new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return usuario; }
        @Override public String idPedido() { return "pedido-usuario"; }
        @Override public String canal() { return "hud"; }
        @Override public String pedidoOriginal() { return "desfaz isso"; }
    }; }
    @BeforeEach void preparar() throws Exception {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        memoria = new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json, relogio, props.zona());
        procedimentos = new ProcedimentosService(pasta.resolve("procedimentos"), json, relogio);
        desfazedor = criar(diario);
        when(lixeira.online()).thenReturn(true);
        var dados = json.createObjectNode().put("nome", "nota.md").put("id", "11111111-1111-1111-1111-111111111111");
        when(lixeira.executar(anyString(), any(), any(), anyString())).thenAnswer(c -> new ResultadoPc(c.getArgument(3), true, "nota.md", null, null, false, dados));
        when(edicoes.preparar(anyString(), any(), any())).thenReturn(new ResultadoPc("preparo", true, "ok", null, null, false, dados));
    }
    DesfazedorDeAcoes criar(DiarioDeAcoes d) { return new DesfazedorDeAcoes(d, edicoes, lixeira, operacoes, memoria, procedimentos, modelos, tarefas, json); }
    @AfterEach void fechar() { diario.fechar(); }
    String registrar(Desfazer d) { return diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "mudei nota.md", OrigemDaAcao.HUD, 42, d); }

    @ParameterizedTest @ValueSource(strings = {"edicao", "lixeira", "criacao", "copia", "restauracao"})
    void desfazOperacoesDoPcUmaVez(String tipo) throws Exception {
        String idTecnico = "11111111-1111-1111-1111-111111111111";
        Desfazer d = switch (tipo) { case "edicao" -> new Desfazer.Edicao(idTecnico); case "lixeira" -> new Desfazer.Lixeira(idTecnico); default -> new Desfazer.Arquivo(idTecnico); };
        String id = registrar(d);
        var resposta = desfazedor.desfazer(id, contexto);
        assertNull(resposta.confirmacao());
        assertEquals("desfeita", diario.localizar(id, 42).orElseThrow().estadoDesfazer());
        assertTrue(desfazedor.desfazer(id, contexto).texto().contains("já foi desfeita"));
        verify(lixeira, times(1)).executar(anyString(), any(), any(), anyString());
        assertEquals(1, diario.deHoje().acoes().stream().filter(a -> a.tipo() == TipoDeAcao.DESFAZER_ACAO).count());
    }

    @Test void desfazAnotacaoEImpedeReanotarAssunto() throws Exception {
        var r = memoria.anotar(42, "perfil", "", "cidade", "Moro em Curitiba", "anotador:hud", "anotador-1");
        String id = registrar(new Desfazer.Anotacao(List.of(r.id())));
        assertTrue(desfazedor.desfazer(id, contexto).texto().contains("Tirei"));
        assertNull(memoria.ler(42, r.id()));
        assertTrue(memoria.assuntoRecusado(42, "perfil", "", "cidade"));
    }

    @Test void voltaParaRevisaoExataDaMemoria() throws Exception {
        var r = memoria.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "usuario:hud", "pedido-1");
        memoria.atualizar(42, r.id(), 1, "São Paulo", "declaracao_usuario", "usuario:hud", "pedido-2");
        var id = registrar(new Desfazer.Memoria(r.id(), 1));
        assertTrue(desfazedor.desfazer(id, contexto).texto().contains("versão anterior"));
        assertEquals("Curitiba", memoria.ler(42, r.id()).conteudo());
        assertFalse(memoria.assuntoRecusado(42, "perfil", "", "cidade"));
    }

    @Test void recusaApagarOuVoltarMemoriaAlteradaDepois() throws Exception {
        var r = memoria.registrar(42, "perfil", "", "cidade", "Curitiba", "declaracao_usuario", "usuario:hud", "pedido-1");
        String id = registrar(new Desfazer.Anotacao(List.of(r.id())));
        memoria.atualizar(42, r.id(), 1, "São Paulo", "declaracao_usuario", "usuario:hud", "pedido-2");
        assertTrue(desfazedor.desfazer(id, contexto).texto().contains("mudou"));
        assertEquals("São Paulo", memoria.ler(42, r.id()).conteudo());
    }

    @Test void desfazProcedimentoNovoEAtualizadoEmSequencia() throws Exception {
        var g = procedimentos.gravar(42, "editar-notas", "Editar notas", "Ao editar uma nota", "Leia antes de editar.");
        String criacao = registrar(new Desfazer.Procedimento(g.nome(), 0));
        procedimentos.gravar(42, g.nome(), "Editar notas", "Ao editar uma nota", "Confira a versão e depois edite.");
        String atualizacao = registrar(new Desfazer.Procedimento(g.nome(), 1));
        assertTrue(desfazedor.desfazer(atualizacao, contexto).texto().contains("versão anterior"));
        assertEquals("Leia antes de editar.", procedimentos.ler(42, g.nome()).orElseThrow().corpo());
        assertTrue(desfazedor.desfazer(criacao, contexto).texto().contains("Tirei"));
        assertTrue(procedimentos.ler(42, g.nome()).isEmpty());
    }

    @Test void desfazTrocaDeModeloERevogaAgendamento() throws Exception {
        when(modelos.modo()).thenReturn(ModoDeIa.LOCAL);
        String modelo = diario.registrar(TipoDeAcao.TROCA_DE_MODELO, "modo local", OrigemDaAcao.HUD, 42, new Desfazer.Modelo("auto", "local"));
        assertTrue(desfazedor.desfazer(modelo, contexto).texto().contains("modo de IA anterior"));
        verify(modelos).restaurar(ModoDeIa.AUTO, "hud");
        when(tarefas.revogar("tarefa", 42)).thenReturn(true);
        String tarefa = registrar(new Desfazer.Tarefa("tarefa"));
        assertTrue(desfazedor.desfazer(tarefa, contexto).texto().contains("Edições já executadas"));
        verify(tarefas).revogar("tarefa", 42);
    }

    @Test void trocaPosteriorMesmoNoMesmoInstanteImpedeReversaoAntiga() {
        when(modelos.modo()).thenReturn(ModoDeIa.LOCAL);
        String primeiro = diario.registrar(TipoDeAcao.TROCA_DE_MODELO, "modo local", OrigemDaAcao.HUD, 42, new Desfazer.Modelo("auto", "local"));
        diario.registrar(TipoDeAcao.TROCA_DE_MODELO, "modo local de novo", OrigemDaAcao.HUD, 42, new Desfazer.Modelo("claude", "local"));
        assertTrue(desfazedor.desfazer(primeiro, contexto).texto().contains("outra troca"));
        verify(modelos, never()).restaurar(any(), anyString());
    }

    @Test void janelaDe24HorasIdentidadeEAcoesSemVolta() throws Exception {
        String antigo = registrar(new Desfazer.Edicao("edicao"));
        relogio.avancar(Duration.ofHours(25));
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "outro usuário", OrigemDaAcao.HUD, 99, new Desfazer.Arquivo("outro"));
        assertTrue(desfazedor.desfazer("", contexto).texto().contains("24 horas"));
        assertTrue(desfazedor.desfazer(antigo, contexto(99)).texto().contains("Não encontrei"));
        String semVolta = diario.registrar(TipoDeAcao.ROTINA, "rodei backup", OrigemDaAcao.HUD, 42, null);
        assertTrue(desfazedor.desfazer(semVolta, contexto).texto().contains("Não tenho como desfazer"));
        verifyNoInteractions(lixeira);
        assertTrue(desfazedor.desfazer(antigo, contexto).texto().contains("versão anterior"));
    }

    @Test void resultadoIncertoFicaReservadoEAConclusaoTardiaNaoReexecuta() throws Exception {
        var arquivo = new dev.jarvis.conversa.ContextoArquivo();
        arquivo.registrar(json.createObjectNode().put("id", "11111111-1111-1111-1111-111111111111")
                .put("nome", "nota.md").put("formato", "md").put("versaoEsperada", "a".repeat(64)), true);
        OuvinteProgresso local = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return "pedido"; }
            @Override public String canal() { return "hud"; }
            @Override public dev.jarvis.conversa.ContextoArquivo contextoArquivo() { return arquivo; }
        };
        when(lixeira.executar(anyString(), any(), any(), anyString())).thenThrow(new IllegalStateException("sem resposta"));
        String id = registrar(new Desfazer.Arquivo("arquivo"));
        assertTrue(desfazedor.desfazer(id, local).texto().contains("incerto"));
        AcaoRegistrada a = diario.localizar(id, 42).orElseThrow();
        assertEquals("em_andamento", a.estadoDesfazer());
        assertEquals(a.comandoDesfazer(), arquivo.estado().operacaoPendente());
        assertTrue(desfazedor.desfazer(id, local).texto().contains("Não executei de novo"));
        when(operacoes.resultado(a.comandoDesfazer())).thenReturn(Optional.of(new ResultadoPc(a.comandoDesfazer(), true, "ok", null, null)));
        assertTrue(desfazedor.desfazer(id, local).texto().contains("confirmou"));
        assertTrue(arquivo.estado().operacaoPendente().isEmpty());
        assertTrue(arquivo.estado().precisaReler());
        verify(lixeira, times(1)).executar(anyString(), any(), any(), anyString());
    }

    @Test void reservaEDesfeitoSobrevivemAoRestart() throws Exception {
        String id = registrar(new Desfazer.Arquivo("arquivo"));
        desfazedor.desfazer(id, contexto); diario.fechar();
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        assertTrue(criar(diario).desfazer(id, contexto).texto().contains("já foi desfeita"));
        assertEquals(new Desfazer.Arquivo("arquivo"), diario.localizar(id, 42).orElseThrow().desfazer());
        verify(lixeira, times(1)).executar(anyString(), any(), any(), anyString());
    }
}
