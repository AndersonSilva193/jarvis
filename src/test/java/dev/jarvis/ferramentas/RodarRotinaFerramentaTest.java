package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.autonomia.PoliticaDeAutonomia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.pc.OperacoesPcService;
import tools.jackson.databind.json.JsonMapper;

class RodarRotinaFerramentaTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private OuvinteProgresso contexto(String idPedido) {
        return new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
            @Override public String idPedido() { return idPedido; }
            @Override public String canal() { return "telegram"; }
            @Override public String pedidoOriginal() { return "rode os testes do agendabarber"; }
        };
    }

    @Test void pedeAprovacaoComPerguntaNaturalEmVezDeProtocolo() {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "testes_agendabarber", "roda a suíte de testes", List.of("maven"))));
        when(e2.online()).thenReturn(true);
        var aprovacoes = new Aprovacoes();
        var ferramenta = new RodarRotinaFerramenta(e2, aprovacoes, json);

        var resultado = ferramenta.executarDetalhado(
                json.createObjectNode().put("nome", "testes_agendabarber"), contexto("pedido-1"));

        String pergunta = "Vou rodar a rotina testes_agendabarber no seu PC; ela executa código na "
                + "sua máquina e eu aviso quando terminar. Posso?";
        assertEquals(pergunta, resultado.confirmacao().pergunta());
        assertEquals(pergunta, resultado.texto(),
                "o modelo repete a pergunta; não há palavra exata a cobrar do usuário");
        verify(e2, never()).enfileirar(any(), any(), any(), anyString(), anyLong());
    }

    @Test void semAprovacaoNadaVaiAoPc() {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "testes_agendabarber", "roda a suíte de testes", List.of("maven"))));
        when(e2.online()).thenReturn(false);
        var ferramenta = new RodarRotinaFerramenta(e2, new Aprovacoes(), json);

        var resultado = ferramenta.executarDetalhado(
                json.createObjectNode().put("nome", "testes_agendabarber"), contexto("pedido-1"));

        assertNull(resultado.confirmacao());
        assertEquals("Agente do PC offline; rotina não iniciada.", resultado.texto());
    }

    @TempDir
    Path pasta;

    @Test void comFazEAvisaRodaNaHoraSemPendenciaERegistraNoDiario() throws Exception {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "backup_notas", "copia as notas", List.of("arquivos"))));
        when(e2.online()).thenReturn(true);
        when(e2.iniciarRotina(eq("backup_notas"), anyString(), eq("telegram"), eq(42L)))
                .thenReturn("Operação c1 enviada; consulte o estado pelo ID.");
        Path autonomia = pasta.resolve("autonomia.md");
        Files.writeString(autonomia, "## faz_e_avisa\n- rotina.rodar:backup_notas\n");
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
        var diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        try {
            var aprovacoes = new Aprovacoes(relogio, diario, new PoliticaDeAutonomia(autonomia));
            var ferramenta = new RodarRotinaFerramenta(e2, aprovacoes, json);

            var resultado = ferramenta.executarDetalhado(
                    json.createObjectNode().put("nome", "backup_notas"), contexto("pedido-1"));

            assertNull(resultado.confirmacao(), "a política liberou: não há pergunta");
            assertEquals("Operação c1 enviada; consulte o estado pelo ID.", resultado.texto());
            verify(e2).iniciarRotina(eq("backup_notas"), anyString(), eq("telegram"), eq(42L));
            assertTrue(aprovacoes.pendenciaDe(42, "pedido-2").isEmpty());

            diario.aguardar();
            var acoes = diario.doDia(LocalDate.parse("2026-10-15")).acoes();
            assertEquals(1, acoes.size());
            assertEquals(TipoDeAcao.ROTINA, acoes.getFirst().tipo());
            assertEquals("Executar rotina backup_notas", acoes.getFirst().descricao());
            assertTrue(acoes.getFirst().semPergunta(), "feita sem pergunta, e o diário sabe");
        } finally {
            diario.fechar();
        }
    }

    @Test void proibidaNaoChegaAoPc() throws Exception {
        var e2 = mock(OperacoesPcService.class);
        when(e2.rotinas()).thenReturn(List.of(new OperacoesPcService.RotinaPublica(
                "deploy", "publica o site", List.of("git"))));
        when(e2.online()).thenReturn(true);
        Path autonomia = pasta.resolve("autonomia.md");
        Files.writeString(autonomia, "## proibido\n- rotina.rodar:deploy\n");
        var aprovacoes = new Aprovacoes(java.time.Clock.systemUTC(), null,
                new PoliticaDeAutonomia(autonomia));
        var ferramenta = new RodarRotinaFerramenta(e2, aprovacoes, json);

        var resultado = ferramenta.executarDetalhado(
                json.createObjectNode().put("nome", "deploy"), contexto("pedido-1"));

        assertNull(resultado.confirmacao());
        assertTrue(resultado.texto().startsWith("Não posso rodar a rotina deploy"), resultado.texto());
        verify(e2, never()).iniciarRotina(anyString(), anyString(), anyString(), anyLong());
    }
}
