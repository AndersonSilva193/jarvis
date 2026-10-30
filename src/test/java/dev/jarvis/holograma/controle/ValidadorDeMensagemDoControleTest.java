package dev.jarvis.holograma.controle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

class ValidadorDeMensagemDoControleTest {

    private final ValidadorDeMensagemDoControle validador = new ValidadorDeMensagemDoControle(JsonMapper.builder().build());

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"t\":\"girar\",\"dx\":0.02,\"dy\":-0.01}",
            "{\"t\":\"soltar\",\"vx\":1.5,\"vy\":0}",
            "{\"t\":\"zoom\",\"f\":1.05}",
            "{\"t\":\"explodir\",\"v\":0.6}",
            "{\"t\":\"explodir\",\"dv\":-0.04}",
            "{\"t\":\"cursor\",\"x\":0.4,\"y\":0.7}",
            "{\"t\":\"cursorFim\"}",
            "{\"t\":\"selecionar\"}",
            "{\"t\":\"resetar\"}",
            "{\"t\":\"mao\",\"v\":1}",
            "{\"t\":\"toque\",\"v\":0}",
            "{\"t\":\"gesto\",\"n\":\"Pinça\"}",
            "{\"t\":\"gesto\",\"n\":\"Explosão 45%\"}",
            "{\"t\":\"gesto\",\"n\":\"Resetando…\"}",
            "{\"t\":\"eco\",\"n\":17}",
    })
    void aceitaCadaTipoDoContrato(String texto) {
        var quadro = validador.doControle(texto).orElseThrow();
        assertEquals(1, quadro.eventos().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "nada",
            "[]",
            "42",
            "{\"t\":\"voar\"}",
            "{\"dx\":0.1,\"dy\":0}",
            "{\"t\":\"girar\",\"dx\":0.1}",
            "{\"t\":\"girar\",\"dx\":0.1,\"dy\":0,\"extra\":1}",
            "{\"t\":\"girar\",\"dx\":2,\"dy\":0}",
            "{\"t\":\"girar\",\"dx\":\"0.1\",\"dy\":0}",
            "{\"t\":\"zoom\",\"f\":3}",
            "{\"t\":\"zoom\",\"f\":0}",
            "{\"t\":\"explodir\",\"v\":1.2}",
            "{\"t\":\"explodir\",\"v\":0.5,\"dv\":0.1}",
            "{\"t\":\"explodir\"}",
            "{\"t\":\"cursor\",\"x\":-0.1,\"y\":0.5}",
            "{\"t\":\"mao\",\"v\":2}",
            "{\"t\":\"mao\",\"v\":true}",
            "{\"t\":\"gesto\",\"n\":\"<img src=x>\"}",
            "{\"t\":\"gesto\",\"n\":\"um rótulo grande demais para a tela\"}",
            "{\"t\":\"eco\",\"n\":1.5}",
            "{\"t\":\"selecionar\",\"x\":0.5}",
            "{\"t\":\"imagem\",\"dados\":\"iVBOR\"}",
    })
    void recusaOQueSaiDoContrato(String texto) {
        assertTrue(validador.doControle(texto).isEmpty(), texto);
    }

    @Test
    void umArrayDeTiposEmOrdemPassaEUmEventoTortoReprovaTudo() {
        var quadro = validador.doControle("[{\"t\":\"girar\",\"dx\":0.01,\"dy\":0},{\"t\":\"selecionar\"}]").orElseThrow();
        assertEquals("girar", quadro.eventos().get(0).path("t").stringValue());
        assertEquals("selecionar", quadro.eventos().get(1).path("t").stringValue());

        assertTrue(validador.doControle("[{\"t\":\"girar\",\"dx\":0.01,\"dy\":0},{\"t\":\"x\"}]").isEmpty());
        assertTrue(validador.doControle("[" + "{\"t\":\"resetar\"},".repeat(8) + "{\"t\":\"resetar\"}]").isEmpty(),
                "nove eventos passam do teto");
    }

    @Test
    void aMensagemSaiReescritaNaoCrua() {
        var evento = validador.doControle("{\"dy\":-0.01,\"t\":\"girar\",\"dx\":2e-2}").orElseThrow().eventos().getFirst();
        assertEquals("{\"t\":\"girar\",\"dx\":0.02,\"dy\":-0.01}", evento.toString());
    }

    @Test
    void aLatenciaFicaNoServidor() {
        var quadro = validador.doControle("{\"t\":\"latencia\",\"p50\":38,\"p95\":71.5,\"k\":30}").orElseThrow();
        assertTrue(quadro.eventos().isEmpty());
        assertEquals(71.5, quadro.latencia().orElseThrow().p95());
        assertEquals(30, quadro.latencia().orElseThrow().amostras());
    }

    @Test
    void textoGrandeDemaisNemELido() {
        String longo = "{\"t\":\"gesto\",\"n\":\"" + "a".repeat(ValidadorDeMensagemDoControle.MAX_CARACTERES) + "\"}";
        assertTrue(validador.doControle(longo).isEmpty());
    }

    @Test
    void aHudSoPodePearDesconectarOuResponderEco() {
        assertEquals("parear", validador.daHud("{\"t\":\"parear\"}").orElseThrow().path("t").stringValue());
        assertEquals("desconectar", validador.daHud("{\"t\":\"desconectar\"}").orElseThrow().path("t").stringValue());
        assertEquals(9, validador.daHud("{\"t\":\"eco\",\"n\":9}").orElseThrow().path("n").intValue());
        assertTrue(validador.daHud("{\"t\":\"girar\",\"dx\":0,\"dy\":0}").isEmpty());
        assertTrue(validador.daHud("{\"t\":\"parear\",\"codigo\":\"x\"}").isEmpty());
    }

    @Test
    void oBaldeDeixaQuarentaPorSegundoEContaODescarte() {
        AtomicLong nanos = new AtomicLong();
        LimiteDeTaxa limite = new LimiteDeTaxa(nanos::get);
        int aceitas = 0;
        for (int i = 0; i < 60; i++) {
            if (limite.permitir()) aceitas++;
        }
        assertEquals(LimiteDeTaxa.POR_SEGUNDO, aceitas);
        assertEquals(20, limite.descartes());

        nanos.addAndGet(Duration.ofMillis(500).toNanos());
        aceitas = 0;
        for (int i = 0; i < 30; i++) {
            if (limite.permitir()) aceitas++;
        }
        assertEquals(20, aceitas, "meio segundo recarrega meio balde");
    }

    @Test
    void descarteDemaisNaJanelaEstoura() {
        AtomicLong nanos = new AtomicLong();
        LimiteDeTaxa limite = new LimiteDeTaxa(nanos::get);
        for (int i = 0; i < LimiteDeTaxa.MAX_DESCARTES; i++) limite.descartar();
        assertTrue(!limite.estourou());
        limite.descartar();
        assertTrue(limite.estourou());

        nanos.addAndGet(Duration.ofMillis(LimiteDeTaxa.JANELA_MS + 1).toNanos());
        limite.descartar();
        assertTrue(!limite.estourou(), "a janela recomeça");
    }

    @Test
    void oBilheteValeUmaVezEPorTrintaSegundos() {
        RelogioFalso relogio = new RelogioFalso();
        BilhetesDoCanal bilhetes = new BilhetesDoCanal(relogio);
        String um = bilhetes.emitir();
        assertTrue(bilhetes.consumir(um));
        assertTrue(!bilhetes.consumir(um));

        String dois = bilhetes.emitir();
        relogio.avancar(BilhetesDoCanal.VALIDADE);
        assertTrue(!bilhetes.consumir(dois));
        assertTrue(!bilhetes.consumir("../../etc"));
    }
}
