package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class OperacoesPcServiceTest {
    @TempDir Path pasta;
    final RelogioFalso relogio = new RelogioFalso();
    OperacoesPcService novo() {
        return new OperacoesPcService(JsonMapper.builder().build(), pasta.resolve("operacoes.json"), relogio);
    }
    ComandoPc comando() { return ComandoPc.nova("volume", "subir", relogio.instant()); }

    @Test void entregaPerdidaRetornaMesmoId() throws Exception {
        var e2 = novo(); var c = comando();
        e2.enfileirar(c, Duration.ofMinutes(2), Duration.ofMinutes(1), "telegram", 42);
        var primeira = e2.proximo(Duration.ZERO).orElseThrow();
        relogio.avancar(Duration.ofSeconds(46));
        var segunda = e2.proximo(Duration.ZERO).orElseThrow();
        assertEquals(primeira.comando().id(), segunda.comando().id());
        assertEquals("entregue", e2.estado(c.id()).orElseThrow().estado());
    }

    @Test void resultadoRepetidoEReinicio() throws Exception {
        var e2 = novo(); var c = comando();
        var futuro = e2.enfileirar(c, Duration.ofMinutes(2), Duration.ofMinutes(1), "hud", 42);
        e2.proximo(Duration.ZERO); assertTrue(e2.recebido(c.id())); assertTrue(e2.executando(c.id()));
        var reiniciado = novo();
        assertEquals("executando", reiniciado.estado(c.id()).orElseThrow().estado());
        var resultado = new ResultadoPc(c.id(), true, "feito", null, null);
        assertTrue(reiniciado.concluir(c.id(), "concluído", resultado, 0));
        assertTrue(reiniciado.concluir(c.id(), "concluído", resultado, 0));
        assertFalse(reiniciado.concluir(c.id(), "concluído",
                new ResultadoPc(c.id(), true, "outro resultado", null, null), 0));
        assertEquals("concluído", reiniciado.estado(c.id()).orElseThrow().estado());
        assertFalse(futuro.isDone(), "o futuro anterior não sobrevive ao reinício da VPS");
    }

    @Test void cancelarAntesDoInicioEExpirar() throws Exception {
        var e2 = novo(); var c = comando();
        e2.enfileirar(c, Duration.ofSeconds(20), Duration.ofMinutes(1), "", 0);
        assertTrue(e2.cancelar(c.id()));
        assertTrue(e2.proximo(Duration.ZERO).isEmpty());
        assertEquals("cancelado", e2.estado(c.id()).orElseThrow().estado());
        var d = comando();
        e2.enfileirar(d, Duration.ofSeconds(20), Duration.ofMinutes(1), "", 0);
        relogio.avancar(Duration.ofSeconds(21)); e2.varrer();
        assertEquals("expirado", e2.estado(d.id()).orElseThrow().estado());
    }

    @Test void resultadoTardioECancelamentoDuranteExecucao() throws Exception {
        var e2 = novo(); var c = comando();
        var futuro = e2.enfileirar(c, Duration.ofMinutes(1), Duration.ofMinutes(3), "telegram", 42);
        e2.proximo(Duration.ZERO); e2.recebido(c.id()); e2.executando(c.id());
        relogio.avancar(Duration.ofSeconds(65));
        assertEquals("executando", e2.estado(c.id()).orElseThrow().estado());
        assertTrue(e2.cancelar(c.id()));
        assertTrue(e2.cancelamentoPedido(c.id()));
        assertTrue(e2.concluir(c.id(), "cancelado",
                new ResultadoPc(c.id(), false, "árvore encerrada", null, null), null));
        assertEquals("árvore encerrada", futuro.get().mensagem());
        e2.pedirAviso(c.id());
        assertEquals(java.util.List.of(c.id()), e2.avisosPendentes());
        assertTrue(e2.marcarAviso(c.id()));
        assertFalse(novo().marcarAviso(c.id()), "reinício não deve enviar um segundo aviso");
    }
}
