package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

class BufferDeLinhasTest {

    @Test
    void linhaPartidaEntreDeltasSoSaiQuandoFecha() {
        BufferDeLinhas buffer = new BufferDeLinhas();
        assertEquals(List.of(), buffer.alimentar("{\"t\":\"me"));
        assertEquals(List.of(), buffer.alimentar("ta\"}"));
        assertEquals(List.of("{\"t\":\"meta\"}"), buffer.alimentar("\n{\"t\""));
        assertEquals(List.of("{\"t\":\"fim\"}"), buffer.alimentar(":\"fim\"}\n"));
        assertEquals("", buffer.resto());
    }

    @Test
    void variasLinhasNumDeltaSoERetornoDoWindows() {
        BufferDeLinhas buffer = new BufferDeLinhas();
        assertEquals(List.of("a", "", "b"), buffer.alimentar("a\r\n\nb\r\nc"));
        assertEquals("c", buffer.resto());
        assertEquals("", buffer.resto());
    }

    @Test
    void restoSemQuebraFinal() {
        BufferDeLinhas buffer = new BufferDeLinhas();
        buffer.alimentar("{\"t\":\"fim\"}\r");
        assertEquals("{\"t\":\"fim\"}", buffer.resto());
    }
}
