package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import dev.jarvis.web.PcDto;
import tools.jackson.databind.json.JsonMapper;

class ConsultaArquivosPcTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test void conteudoEstruturadoPassaSemUsarMensagemLimitada() throws Exception {
        PcService pc = new PcService();
        ComandoPc comando = ComandoPc.nova("ler_arquivo", "", Instant.now());
        var futuro = pc.enfileirar(comando);
        pc.proximoComando(Duration.ofMillis(1), null);
        String texto = "a".repeat(6000);
        var dados = json.createObjectNode().put("conteudo", texto);
        var dto = new PcDto.Resultado(comando.id(), true, "consulta concluída", null, false, dados);
        assertTrue(dto.erros().isEmpty());
        assertTrue(pc.concluir(dto.paraDominio()));
        assertEquals(texto, futuro.get().dados().path("conteudo").stringValue());
        assertTrue(futuro.get().mensagem().length() <= ResultadoPc.MAX_MENSAGEM);
    }

    @Test void dadoGrandeOuEmAcaoErradaEhRecusado() {
        var enorme = json.createObjectNode().put("conteudo", "x".repeat(33000));
        var dto = new PcDto.Resultado("abc", true, "ok", null, false, enorme);
        assertFalse(dto.erros().isEmpty());
        var multibyte = json.createObjectNode().put("conteudo", "é".repeat(20_000));
        assertFalse(new PcDto.Resultado("abc", true, "ok", null, false, multibyte).erros().isEmpty());
        assertEquals(ResultadoPc.MAX_MENSAGEM,
                new ResultadoPc("abc", true, "x".repeat(1000), null, null).mensagem().length());
        PcService pc = new PcService();
        ComandoPc comando = ComandoPc.nova("bloquear_tela", "", Instant.now());
        pc.enfileirar(comando);
        assertFalse(pc.concluir(new ResultadoPc(comando.id(), true, "ok", null, null, false,
                json.createObjectNode().put("conteudo", "não pertence a esta ação"))));
    }
}
