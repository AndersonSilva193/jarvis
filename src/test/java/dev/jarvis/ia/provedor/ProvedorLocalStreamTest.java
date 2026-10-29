package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.custo.Origem;
import dev.jarvis.ia.provedor.BancadaLocal.Parcial;
import tools.jackson.databind.JsonNode;

/** O {@code inferir} com stream: parciais em ordem, reconciliação pelo final, inatividade e corte. */
class ProvedorLocalStreamTest {

    @TempDir Path pasta;
    private BancadaLocal bancada;

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private static PedidoAoModelo pedido(Duration prazo) {
        return new PedidoAoModelo(InstrucoesDeSistema.de(new BlocoDeSistema("Gere NDJSON.", null)),
                List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "Gere: cubo")), null,
                OpcoesDoPedido.curta(Origem.HOLOGRAMA, 4000, prazo).comTemperatura(0.4));
    }

    @Test
    void parciaisEmOrdemEOFinalCompletaOQueFaltou() throws Exception {
        bancada = new BancadaLocal(pasta);
        bancada.transmite(List.of(new Parcial(0, "ab"), new Parcial(2, "cd")), "abcdef");
        List<String> deltas = new CopyOnWriteArrayList<>();

        RespostaDoModelo resposta = bancada.local.transmitir(pedido(Duration.ofSeconds(10)), deltas::add,
                new Cancelamento());

        assertEquals(List.of("ab", "cd", "ef"), deltas);
        assertEquals(List.of(true, true), bancada.parciaisAceitas);
        assertEquals("abcdef", String.join("", resposta.textos()));
        JsonNode dados = bancada.recebidos.get(0);
        assertTrue(dados.path("stream").asBoolean());
        assertFalse(dados.path("think").asBoolean(true), "o raciocínio tem de ir desligado");
        assertEquals(0.4, dados.path("temperatura").asDouble());
        assertTrue(dados.path("tools").isMissingNode());
    }

    @Test
    void repetidoEIgnoradoESobrepostoSoEntraONovo() throws Exception {
        bancada = new BancadaLocal(pasta);
        bancada.transmite(List.of(new Parcial(0, "ab"), new Parcial(0, "ab"), new Parcial(4, "ef"),
                new Parcial(1, "bcd")), "abcdef");
        List<String> deltas = new CopyOnWriteArrayList<>();

        bancada.local.transmitir(pedido(Duration.ofSeconds(10)), deltas::add, new Cancelamento());

        // O "ef" chegou antes da hora (buraco) e esperou; o final o entregou.
        assertEquals(List.of("ab", "cd", "ef"), deltas);
    }

    @Test
    void semEscreverNoPrazoDeInatividadeCancelaECai() throws Exception {
        bancada = new BancadaLocal(pasta); // IA_LOCAL_TIMEOUT_SEGUNDOS = 1
        bancada.calado();

        FalhaLocal falha = assertThrows(FalhaLocal.class, () -> bancada.local.transmitir(
                pedido(Duration.ofSeconds(30)), texto -> { }, new Cancelamento()));

        assertEquals(ProvedorLocal.TIMEOUT, falha.motivo());
        assertTrue(bancada.e2.cancelamentoPedido(bancada.ids.get(0)), "o PC não foi avisado");
        assertFalse(bancada.pc.parcial(bancada.ids.get(0), 0, "tarde"), "parcial depois do corte tem de dar 410");
    }

    @Test
    void oCorteDeForaParaAEsperaEOAgenteRecebe410() throws Exception {
        bancada = new BancadaLocal(pasta);
        bancada.calado();
        Cancelamento cancelamento = new Cancelamento();
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                return;
            }
            cancelamento.cancelar("limite");
        });

        FalhaLocal falha = assertThrows(FalhaLocal.class, () -> bancada.local.transmitir(
                pedido(Duration.ofSeconds(30)), texto -> { }, cancelamento));

        assertEquals(ProvedorLocal.CANCELADO, falha.motivo());
        assertFalse(bancada.pc.parcial(bancada.ids.get(0), 0, "x"));
    }
}
