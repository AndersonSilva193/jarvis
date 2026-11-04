package dev.jarvis.autonomia;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.aprovacao.Aprovacoes;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.tarefas.ContextoDaTarefa;
import dev.jarvis.tarefas.SuspensaoDaTarefa;
import tools.jackson.databind.json.JsonMapper;

class ConcessoesDePlanoTest {
    @TempDir Path pasta;
    @Test void reciboCriticoValeSomenteNaTarefaEProibicaoAindaVence() throws Exception {
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso();
        var caminho = pasta.resolve("concessoes.json");
        var iniciais = new Concessoes(json, relogio, ZoneId.of("UTC"), caminho, null);
        assertThrows(IllegalArgumentException.class, () -> iniciais.conceder(AcaoAutonoma.PC_REINICIAR,
                null, Concessao.Escopo.TAREFA, null, "abc12345", Concessao.Origem.PLANO, "sim", 42, "telegram"));
        iniciais.concederPlano(AcaoAutonoma.PC_REINICIAR, null, "abc12345", "Plano aprovado", 42, "telegram");
        var concessoes = new Concessoes(json, relogio, ZoneId.of("UTC"), caminho, null);
        assertTrue(concessoes.vigente(AcaoAutonoma.PC_REINICIAR, null).isEmpty());
        assertTrue(concessoes.vigenteNaTarefa(AcaoAutonoma.PC_REINICIAR, null, "outra").isEmpty());
        var politica = new PoliticaDeAutonomia(pasta.resolve("autonomia.md"), concessoes);
        var aprovacoes = new Aprovacoes(relogio, null, politica); var feitas = new AtomicInteger();
        try (var contexto = ContextoDaTarefa.entrar("abc12345", 42, v -> { })) {
            assertEquals(Nivel.FAZ_E_AVISA, aprovacoes.encaminhar(AcaoAutonoma.PC_REINICIAR, null,
                    Aprovacoes.TIPO_PC_ENERGIA, "reiniciar PC", "Posso reiniciar?", Aprovacoes.Risco.CRITICO,
                    42, "pedido", "tarefa", Aprovacoes.frase(() -> "feito " + feitas.incrementAndGet())).nivel());
        }
        assertEquals(1, feitas.get()); assertTrue(aprovacoes.pendenciaDe(42, "nova").isEmpty());
        try (var contexto = ContextoDaTarefa.entrar("outra", 42, v -> { })) {
            assertThrows(SuspensaoDaTarefa.class, () -> aprovacoes.encaminhar(AcaoAutonoma.PC_REINICIAR, null,
                    Aprovacoes.TIPO_PC_ENERGIA, "reiniciar PC", "Posso reiniciar?", Aprovacoes.Risco.CRITICO,
                    42, "pedido", "tarefa", Aprovacoes.frase(() -> "feito " + feitas.incrementAndGet())));
        }
        Files.writeString(pasta.resolve("autonomia.md"), "## proibido\n- pc.reiniciar\n");
        try (var contexto = ContextoDaTarefa.entrar("abc12345", 42, v -> { })) {
            assertEquals(Nivel.PROIBIDO, aprovacoes.encaminhar(AcaoAutonoma.PC_REINICIAR, null,
                    Aprovacoes.TIPO_PC_ENERGIA, "reiniciar PC", "Posso reiniciar?", Aprovacoes.Risco.CRITICO,
                    42, "pedido", "tarefa", Aprovacoes.frase(() -> "feito " + feitas.incrementAndGet())).nivel());
        }
        assertEquals(1, feitas.get()); concessoes.encerrarTarefa("abc12345"); assertTrue(concessoes.ativas().isEmpty());
    }
}
