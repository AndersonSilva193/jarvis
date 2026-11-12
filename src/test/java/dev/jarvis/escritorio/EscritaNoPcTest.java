package dev.jarvis.escritorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;

class EscritaNoPcTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<ComandoPc> enviados = new ArrayList<>();

    private PedidoDeEscrita pedido() {
        return new PedidoDeEscrita("p1", "acrescentar_linhas", "agenda.csv",
                json.createObjectNode().put("x", 1), "2026-11-12T10:00:00Z");
    }

    private EscritaNoPc escrita(boolean online, boolean conhece, CompletableFuture<ResultadoPc> resposta) {
        return new EscritaNoPc(comando -> { enviados.add(comando); return resposta; },
                () -> online, acao -> conhece, Duration.ofMillis(200));
    }

    private static CompletableFuture<ResultadoPc> pronto(boolean ok, String mensagem) {
        return CompletableFuture.completedFuture(new ResultadoPc("c", ok, mensagem, null, null));
    }

    @Test
    void offlineNaoEnvia() {
        assertEquals(EscritaNoPc.Estado.PC_OFFLINE, escrita(false, true, pronto(true, "")).enviar(pedido()).estado());
        assertTrue(enviados.isEmpty());
    }

    @Test
    void agenteAntigoNaoEnvia() {
        assertEquals(EscritaNoPc.Estado.AGENTE_ANTIGO, escrita(true, false, pronto(true, "")).enviar(pedido()).estado());
        assertTrue(enviados.isEmpty());
    }

    @Test
    void resultadoOkEGravado() {
        assertEquals(EscritaNoPc.Estado.GRAVADO, escrita(true, true, pronto(true, "planilha gravada")).enviar(pedido()).estado());
        assertEquals(1, enviados.size());
        ComandoPc comando = enviados.get(0);
        assertEquals("acrescentar_linhas", comando.acao());
        assertEquals("agenda.csv", comando.dados().path("arquivo").asString());
        assertEquals(1, comando.dados().path("x").asInt());
    }

    @Test
    void travadoEIdInexistenteVemDoPrefixo() {
        assertEquals(EscritaNoPc.Estado.ARQUIVO_TRAVADO,
                escrita(true, true, pronto(false, "arquivo_travado: aberto no Excel")).enviar(pedido()).estado());
        assertEquals(EscritaNoPc.Estado.ID_INEXISTENTE,
                escrita(true, true, pronto(false, "id_inexistente: nenhuma linha")).enviar(pedido()).estado());
        assertEquals(EscritaNoPc.Estado.FALHOU,
                escrita(true, true, pronto(false, "nao_permitido: x")).enviar(pedido()).estado());
    }

    @Test
    void semRespostaATempoFalha() {
        assertEquals(EscritaNoPc.Estado.FALHOU, escrita(true, true, new CompletableFuture<>()).enviar(pedido()).estado());
    }

    @Test
    void cadaEnvioUsaUmComandoNovo() {
        EscritaNoPc escrita = escrita(true, true, pronto(true, ""));
        escrita.enviar(pedido());
        escrita.enviar(pedido());
        assertTrue(!enviados.get(0).id().equals(enviados.get(1).id()), "o PC guarda o resultado pelo id do comando");
    }
}
