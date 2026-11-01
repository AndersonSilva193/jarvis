package dev.jarvis.conversa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ContextoArquivoTest {
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String HASH = "a".repeat(64);
    private final JsonMapper json = JsonMapper.builder().build();

    private tools.jackson.databind.JsonNode leitura() {
        return json.createObjectNode().put("id", ID).put("nome", "gastos.xlsx")
                .put("formato", "xlsx").put("versaoEsperada", HASH);
    }

    @Test void leituraExpiraMasIdentidadeContinua() {
        var relogio = mock(Clock.class);
        Instant agora = Instant.now();
        when(relogio.instant()).thenReturn(agora);
        var contexto = new ContextoArquivo(relogio);
        assertTrue(contexto.registrar(leitura(), true));
        assertEquals("", contexto.erroEdicao(ID, HASH));
        when(relogio.instant()).thenReturn(agora.plus(Duration.ofMinutes(11)));
        assertTrue(contexto.erroEdicao(ID, HASH).contains("Releia"));
        assertEquals(ID, contexto.estado().id());
        var atual = new ContextoArquivo();
        atual.restaurar(contexto.estado());
        assertEquals(ID, atual.estado().id());
        assertTrue(atual.erroEdicao(ID, HASH).contains("Releia"));
        assertTrue(atual.registrar(leitura(), true));
        assertEquals("", atual.erroEdicao(ID, HASH));
    }

    @Test void versaoDivergenteNaoEhSubstituidaSilenciosamente() {
        var contexto = new ContextoArquivo();
        contexto.registrar(leitura(), true);
        assertTrue(contexto.erroEdicao(ID, "b".repeat(64)).contains("diverge"));
        assertEquals(HASH, contexto.estado().versao());
        assertTrue(contexto.erroEdicao(ID, HASH).contains("Releia"));
    }

    @Test void consultaNaoDesbloqueiaResultadoIncertoNemAutorizaOutroArquivo() {
        var contexto = new ContextoArquivo();
        contexto.registrar(leitura(), true);
        String operacao = "22222222-2222-2222-2222-222222222222";
        contexto.operacaoPendente(operacao);
        contexto.limpar();
        contexto.criado("outro.md");
        assertFalse(contexto.registrar(leitura(), true));
        assertTrue(contexto.erroEdicao(ID, HASH).contains(operacao));
        contexto.operacaoTerminou("33333333-3333-3333-3333-333333333333");
        assertEquals(operacao, contexto.estado().operacaoPendente());
        contexto.operacaoTerminou(operacao);
        assertTrue(contexto.erroEdicao(ID, HASH).contains("Releia"));
    }

    @Test void metadadosInvalidosNaoSeTornamAlvo() {
        var contexto = new ContextoArquivo();
        var dados = (tools.jackson.databind.node.ObjectNode) leitura();
        dados.put("nome", "../gastos.xlsx");
        assertFalse(contexto.registrar(dados, true));
        contexto.restaurar(new ContextoArquivo.Estado(ID, "gastos.xlsx", "xlsx", "hash inventado", false, ""));
        assertNull(contexto.estado());
    }
}
