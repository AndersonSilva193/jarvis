package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** O X-Pc-Acoes: a VPS só manda ação de planilha a um agente que disse conhecê-la. */
class AcoesDoAgenteTest {

    private final PcService pc = new PcService();

    private void consultar(String acoes) {
        pc.proximoComando(Duration.ZERO, "", null, null, acoes);
    }

    @Test
    void semCabecalhoNaoConheceNada() {
        assertFalse(pc.agenteConhece("acrescentar_linhas"));
        consultar(null);
        assertFalse(pc.agenteConhece("acrescentar_linhas"));
    }

    @Test
    void cabecalhoComAsTresConhece() {
        consultar("acrescentar_linhas,alterar_linha,remover_linha");
        assertTrue(pc.agenteConhece("acrescentar_linhas"));
        assertTrue(pc.agenteConhece("alterar_linha"));
        assertTrue(pc.agenteConhece("remover_linha"));
        assertFalse(pc.agenteConhece("desligar"));
    }

    @Test
    void cabecalhoAusenteDepoisApaga() {
        consultar("acrescentar_linhas");
        assertTrue(pc.agenteConhece("acrescentar_linhas"));
        consultar(null);
        assertFalse(pc.agenteConhece("acrescentar_linhas"), "agente trocado por um antigo");
    }

    @Test
    void lixoNoCabecalhoEIgnorado() {
        consultar(" acrescentar_linhas , X-Evil\u0007, a b," + "x".repeat(41) + ",remover_linha");
        assertTrue(pc.agenteConhece("acrescentar_linhas"));
        assertTrue(pc.agenteConhece("remover_linha"));
        assertFalse(pc.agenteConhece("x-evil"));
        assertFalse(pc.agenteConhece("a b"));
        assertFalse(pc.agenteConhece(null));
    }

    @Test
    void quandoOPcVoltaAsAcoesDeleJaEstaoRegistradas() {
        java.util.List<Boolean> conheciaNoEvento = new java.util.ArrayList<>();
        PcService[] servico = new PcService[1];
        servico[0] = new PcService(new RelogioFalso("2026-11-12T10:00:00Z"),
                evento -> conheciaNoEvento.add(servico[0].agenteConhece("acrescentar_linhas")));
        servico[0].proximoComando(Duration.ZERO, "", null, null, "acrescentar_linhas");
        assertTrue(!conheciaNoEvento.isEmpty() && conheciaNoEvento.get(0),
                "a fila esvazia no PcVoltou e acharia que o agente é antigo: " + conheciaNoEvento);
    }

    @Test
    void comandoAceitaAsAcoesDePlanilha() {
        for (String acao : ComandoPc.PLANILHA)
            assertDoesNotThrow(() -> new ComandoPc("id", acao, "", "2026-11-12T00:00:00Z"));
    }
}
