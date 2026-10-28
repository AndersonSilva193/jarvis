package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.custo.Origem;
import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;

/** Quando o local atende, quando o Claude assume, e o motivo de cada reserva no medidor. */
class ProvedorDeApoioTest {

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final AtomicInteger chamadasAoClaude = new AtomicInteger();
    private final ProvedorDeModelo claude = pedido -> {
        chamadasAoClaude.incrementAndGet();
        return new RespostaDoModelo(List.of(new Bloco.Texto("do Claude")), MotivoDeParada.FIM, null);
    };

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private static PedidoAoModelo pedido() {
        return new PedidoAoModelo(InstrucoesDeSistema.de(new BlocoDeSistema("narre", null)),
                List.of(Mensagem.texto(Mensagem.Papel.USUARIO, "dados")), ListaDeFerramentas.vazia(),
                OpcoesDoPedido.de(Origem.BRIEFING));
    }

    @Test
    @DisplayName("local disponível e aprovado: o Claude nem é chamado")
    void localAtende() throws Exception {
        bancada = new BancadaLocal(pasta).responde("do local");
        RespostaDoModelo resposta = bancada.apoio(claude).responder(pedido(), r -> true);

        assertEquals(List.of("do local"), resposta.textos());
        assertEquals(0, chamadasAoClaude.get());
        assertTrue(bancada.medidor.localDeHoje().reservas().isEmpty());
        assertEquals(1L, bancada.medidor.localDeHoje().chamadas().get(Origem.BRIEFING));
    }

    @Test
    @DisplayName("validador reprova o local: o Claude responde e a reserva conta 'reprovado'")
    void reprovadoVaiAoClaude() throws Exception {
        bancada = new BancadaLocal(pasta).responde("[0] faltou marca");
        RespostaDoModelo resposta = bancada.apoio(claude)
                .responder(pedido(), r -> r.textos().getFirst().contains("[1]"));

        assertEquals(List.of("do Claude"), resposta.textos());
        assertEquals(1, chamadasAoClaude.get());
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get(ProvedorDeApoio.REPROVADO));
    }

    @Test
    @DisplayName("indisponível: o local nem é tentado, e o motivo da indisponibilidade é a reserva")
    void indisponivel() throws Exception {
        bancada = new BancadaLocal(pasta);
        bancada.cabecalho(BancadaLocal.PRONTA.replace("ocupada=0", "ocupada=1"));

        assertEquals(List.of("do Claude"), bancada.apoio(claude).responder(pedido()).textos());
        assertTrue(bancada.recebidos.isEmpty(), "nenhum inferir saiu");
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get(DisponibilidadeLocal.GPU_OCUPADA));
    }

    @Test
    @DisplayName("falha do agente: o código dele é a reserva")
    void falhaDoAgente() throws Exception {
        bancada = new BancadaLocal(pasta).falha("modelo_ausente");
        assertEquals(List.of("do Claude"), bancada.apoio(claude).responder(pedido()).textos());
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get("modelo_ausente"));
    }

    @Test
    @DisplayName("IA_LOCAL_APOIO=false: só o Claude, sem inferir e sem reserva contada")
    void apoioDesligado() throws Exception {
        bancada = new BancadaLocal(pasta, "false").responde("do local");
        assertEquals(List.of("do Claude"), bancada.apoio(claude).responder(pedido()).textos());
        assertTrue(bancada.recebidos.isEmpty());
        assertTrue(bancada.medidor.localDeHoje().reservas().isEmpty());
    }

    @Test
    @DisplayName("sem validador, resposta vazia do local é reprovada")
    void vazioReprovado() throws Exception {
        bancada = new BancadaLocal(pasta).responde("   ");
        assertEquals(List.of("do Claude"), bancada.apoio(claude).responder(pedido()).textos());
    }
}
