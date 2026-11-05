package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** O PC "voltou" é um fato só: primeiro contato ou contato depois de mais de 90 s calado. */
class PcVoltouTest {
    @TempDir Path pasta;

    @Test void publicaNoPrimeiroContatoEDepoisDeMaisDeNoventaSegundos() {
        var relogio = new RelogioFalso(); List<Object> eventos = new ArrayList<>();
        var pc = new PcService(relogio, eventos::add);
        pc.proximoComando(Duration.ZERO, null);
        assertEquals(1, eventos.size()); assertNull(((PcVoltou) eventos.getFirst()).ausencia());

        relogio.avancar(Duration.ofSeconds(25)); pc.proximoComando(Duration.ZERO, null);
        relogio.avancar(Duration.ofSeconds(90)); pc.proximoComando(Duration.ZERO, null);
        assertEquals(1, eventos.size(), "90 s ainda é o PC no ar, não uma volta");

        relogio.avancar(Duration.ofSeconds(91)); pc.proximoComando(Duration.ZERO, null);
        assertEquals(2, eventos.size());
        var volta = (PcVoltou) eventos.getLast();
        assertEquals(relogio.instant(), volta.em()); assertEquals(Duration.ofSeconds(91), volta.ausencia());
    }

    @Test void contatoPeloE2PassaPeloMesmoGatilho() throws Exception {
        var e2 = new OperacoesPcService(JsonMapper.builder().build(), pasta.resolve("e2.json"), new RelogioFalso());
        List<Object> eventos = new ArrayList<>();
        var pc = new PcService(e2, CatalogoDoPc.emMemoria(), eventos::add);
        e2.proximo(Duration.ZERO);
        assertEquals(1, eventos.size()); assertInstanceOf(PcVoltou.class, eventos.getFirst());
        e2.proximo(Duration.ZERO); pc.proximoComando(Duration.ZERO, null);
        assertEquals(1, eventos.size(), "o E2 e o caminho antigo não publicam duas vezes");
        assertTrue(pc.online());
    }

    @Test void desligouSaiUmaVezPorAusenciaEORetornoRearma() {
        var relogio = new RelogioFalso(); List<Object> eventos = new ArrayList<>();
        var pc = new PcService(relogio, eventos::add);
        pc.vigiarAusencia();
        assertEquals(0, eventos.size(), "sem contato nenhum não há o que dar como desligado");
        pc.proximoComando(Duration.ZERO, null);
        eventos.clear();

        relogio.avancar(Duration.ofSeconds(60)); pc.vigiarAusencia();
        assertTrue(eventos.isEmpty(), "60 s ainda é o PC no ar");
        relogio.avancar(Duration.ofSeconds(31)); pc.vigiarAusencia(); pc.vigiarAusencia();
        assertEquals(1, eventos.size(), "uma ausência, um aviso: " + eventos);
        var desligou = (PcDesligou) eventos.getFirst();
        assertEquals(relogio.instant().minusSeconds(91), desligou.ultimoContato());

        pc.proximoComando(Duration.ZERO, null);
        assertInstanceOf(PcVoltou.class, eventos.getLast());
        relogio.avancar(Duration.ofSeconds(100)); pc.vigiarAusencia();
        assertInstanceOf(PcDesligou.class, eventos.getLast(), "o contato rearmou a vigia");
    }
}
