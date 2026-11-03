package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.*;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

class AprovacoesResultadoTest {
    @TempDir Path pasta;
    @Test void resultadoEstruturadoEntraNoDiarioUmaVezSemExtrairIdentidadeDoTexto() {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("UTC"));
        var relogio = new RelogioFalso(); var diario = new DiarioDeAcoes(props, JsonMapper.builder().build(), relogio, pasta);
        try {
            var aprovacoes = new Aprovacoes(relogio, diario);
            aprovacoes.pedirComResultado("tarefa_planilha", "agendar dados.xlsx", "Posso?", Aprovacoes.Risco.COMUM, 42, "pedido-1",
                    () -> new Aprovacoes.ResultadoAprovado("Agendada sem dizer o id.", new Desfazer.Tarefa("id-real"), true));
            assertEquals("Agendada sem dizer o id.", aprovacoes.executarPendente(42, "pedido-2", "hud").orElseThrow());
            assertTrue(aprovacoes.executarPendente(42, "pedido-3", "hud").isEmpty());
            var acao = diario.deHoje().acoes().getFirst();
            assertEquals(42, acao.usuario()); assertEquals(new Desfazer.Tarefa("id-real"), acao.desfazer());
            aprovacoes.pedirComResultado("tarefa_planilha", "agendar outra.xlsx", "Posso?", Aprovacoes.Risco.COMUM, 42, "pedido-4",
                    () -> new Aprovacoes.ResultadoAprovado("Falhou.", null, false));
            aprovacoes.executarPendente(42, "pedido-5", "hud");
            assertEquals(1, diario.deHoje().acoes().size());
        } finally { diario.fechar(); }
    }
}
