package dev.jarvis.holograma.controle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.jarvis.holograma.controle.PareamentosDoControle.Papel;
import dev.jarvis.holograma.controle.PareamentosDoControle.Recusa;
import dev.jarvis.pc.RelogioFalso;

class PareamentosDoControleTest {

    private final RelogioFalso relogio = new RelogioFalso();
    private final PareamentosDoControle pareamentos = new PareamentosDoControle(relogio);

    @Test
    void oCodigoLigaOCelularAHudQueOGerouUmaVezSo() {
        String codigo = pareamentos.gerarCodigo("hud").orElseThrow();

        var entrada = pareamentos.entrarComCodigo(codigo, "cel").entrada().orElseThrow();
        assertEquals(Papel.CONTROLE, entrada.papel());
        assertEquals(Optional.of("hud"), entrada.outroLado());
        assertEquals(Optional.of("hud"), pareamentos.outroLado("cel"));
        assertEquals(Optional.of("cel"), pareamentos.outroLado("hud"));

        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.entrarComCodigo(codigo, "cel2").recusa());
    }

    @Test
    void oCodigoVenceEmCincoMinutos() {
        String codigo = pareamentos.gerarCodigo("hud").orElseThrow();
        relogio.avancar(PareamentosDoControle.VALIDADE_DO_CODIGO);
        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.entrarComCodigo(codigo, "cel").recusa());
    }

    @Test
    void umCodigoNovoDerrubaOAnteriorDaMesmaHud() {
        String velho = pareamentos.gerarCodigo("hud").orElseThrow();
        String novo = pareamentos.gerarCodigo("hud").orElseThrow();
        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.entrarComCodigo(velho, "cel").recusa());
        assertTrue(pareamentos.entrarComCodigo(novo, "cel").entrada().isPresent());
    }

    @Test
    void comCelularLigadoAHudNaoGeraCodigo() {
        pareamentos.entrarComCodigo(pareamentos.gerarCodigo("hud").orElseThrow(), "cel");
        assertTrue(pareamentos.gerarCodigo("hud").isEmpty());
    }

    @Test
    void lixoNaoEntra() {
        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.entrarComCodigo("abc", "cel").recusa());
        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.retomar(Papel.CONTROLE, null, "cel").recusa());
    }

    @Test
    void oCelularQueCaiVoltaComAChaveDentroDaTolerancia() {
        String retomada = pareamentos.entrarComCodigo(pareamentos.gerarCodigo("hud").orElseThrow(), "cel")
                .entrada().orElseThrow().retomada();

        var aviso = pareamentos.caiu("cel").orElseThrow();
        assertEquals(Papel.CONTROLE, aviso.quemCaiu());
        assertEquals(Optional.of("hud"), aviso.outroLado());
        assertTrue(pareamentos.outroLado("hud").isEmpty(), "sem celular, o repasse não tem para onde ir");

        relogio.avancar(Duration.ofSeconds(29));
        assertTrue(pareamentos.vencidos().isEmpty());
        var volta = pareamentos.retomar(Papel.CONTROLE, retomada, "cel-novo").entrada().orElseThrow();
        assertTrue(volta.retomou());
        assertEquals(Optional.of("cel-novo"), pareamentos.outroLado("hud"));
    }

    @Test
    void depoisDaToleranciaOParAcaba() {
        String retomada = pareamentos.entrarComCodigo(pareamentos.gerarCodigo("hud").orElseThrow(), "cel")
                .entrada().orElseThrow().retomada();
        pareamentos.caiu("cel");
        relogio.avancar(PareamentosDoControle.TOLERANCIA_DE_QUEDA);

        var fim = pareamentos.vencidos();
        assertEquals(1, fim.size());
        assertEquals(Optional.of("hud"), fim.getFirst().hud());
        assertTrue(fim.getFirst().controle().isEmpty());
        assertFalse(pareamentos.retomar(Papel.CONTROLE, retomada, "cel").entrada().isPresent());
        assertTrue(pareamentos.gerarCodigo("hud").isPresent(), "a HUD pode parear de novo");
    }

    @Test
    void aHudTambemVoltaComASuaChave() {
        pareamentos.entrarComCodigo(pareamentos.gerarCodigo("hud").orElseThrow(), "cel");
        String chave = pareamentos.retomadaDaHud("hud").orElseThrow();
        assertEquals(Papel.HUD, pareamentos.caiu("hud").orElseThrow().quemCaiu());

        assertFalse(pareamentos.retomar(Papel.CONTROLE, chave, "x").entrada().isPresent(),
                "a chave da HUD não serve de celular");
        var volta = pareamentos.retomar(Papel.HUD, chave, "hud2").entrada().orElseThrow();
        assertEquals(Optional.of("cel"), volta.outroLado());
        assertEquals(Optional.of("hud2"), pareamentos.outroLado("cel"));
    }

    @Test
    void desconectarAcabaNaHora() {
        pareamentos.entrarComCodigo(pareamentos.gerarCodigo("hud").orElseThrow(), "cel");
        var fim = pareamentos.encerrar("hud").orElseThrow();
        assertEquals(Optional.of("cel"), fim.controle());
        assertTrue(pareamentos.outroLado("cel").isEmpty());
        assertTrue(pareamentos.caiu("cel").isEmpty());
    }

    @Test
    void aQuedaDaHudApagaOCodigoPendente() {
        String codigo = pareamentos.gerarCodigo("hud").orElseThrow();
        pareamentos.caiu("hud");
        assertEquals(Optional.of(Recusa.INVALIDO), pareamentos.entrarComCodigo(codigo, "cel").recusa());
    }
}
