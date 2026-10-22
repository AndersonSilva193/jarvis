package dev.jarvis.computer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class DetectorDeTravamentoTest {
    private final JsonMapper json = JsonMapper.builder().build();

    private ObjectNode clique(int x, int y) {
        ObjectNode entrada = json.createObjectNode();
        entrada.putArray("coordinate").add(x).add(y);
        return entrada;
    }
    private static byte[] tela(String conteudo) {
        return conteudo.getBytes(StandardCharsets.UTF_8);
    }

    @Test void rolagemQueMudaATelaNaoTrava() {
        var detector = new DetectorDeTravamento();
        ObjectNode entrada = json.createObjectNode().put("scroll_amount", 3);
        for (int i = 1; i <= 5; i++)
            assertFalse(detector.registrar("scroll", entrada, tela("pagina " + i)),
                    "rolagem que revela conteudo novo nao e travamento");
    }

    @Test void cliqueRepetidoSemMudancaTravaNaTerceira() {
        var detector = new DetectorDeTravamento();
        byte[] mesmaTela = tela("dialogo aberto");
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertTrue(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertEquals("parei: repeti left_click 3 vezes sem mudança na tela", detector.motivo());
    }

    @Test void waitNoMeioNaoContaENaoZera() {
        var detector = new DetectorDeTravamento();
        byte[] mesmaTela = tela("carregando");
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("wait", json.createObjectNode().put("duration", 10), mesmaTela),
                "esperar e a resposta certa para tela lenta");
        assertTrue(detector.registrar("left_click", clique(10, 20), mesmaTela),
                "a espera no meio nao zera a contagem dos cliques");
    }

    @Test void entradaDiferenteZeraAContagem() {
        var detector = new DetectorDeTravamento();
        byte[] mesmaTela = tela("menu");
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(30, 40), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
    }

    @Test void ordemDasChavesNaoMudaAAssinatura() {
        var detector = new DetectorDeTravamento();
        byte[] mesmaTela = tela("campo de busca");
        ObjectNode xy = json.createObjectNode().put("x", 1).put("y", 2);
        ObjectNode yx = json.createObjectNode().put("y", 2).put("x", 1);
        assertFalse(detector.registrar("click", xy, mesmaTela));
        assertFalse(detector.registrar("click", yx, mesmaTela));
        assertTrue(detector.registrar("click", xy, mesmaTela),
                "a mesma entrada escrita em outra ordem continua sendo a mesma acao");
    }

    @Test void capturaVaziaNaoMudaNada() {
        var detector = new DetectorDeTravamento();
        byte[] mesmaTela = tela("janela");
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(10, 20), mesmaTela));
        assertFalse(detector.registrar("left_click", clique(10, 20), new byte[0]));
        assertTrue(detector.registrar("left_click", clique(10, 20), mesmaTela));
    }
}
