package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** O SSE da Messages API com stream, na forma que a API manda. */
class LeitorDeStreamAnthropicTest {

    private final LeitorDeStreamAnthropic leitor = new LeitorDeStreamAnthropic(JsonMapper.builder().build());

    private static final String STREAM = """
            event: message_start
            data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],"model":"claude-haiku-5-5","stop_reason":null,"usage":{"input_tokens":812,"cache_creation_input_tokens":0,"cache_read_input_tokens":0,"output_tokens":3}}}

            event: content_block_start
            data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":"","signature":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":0,"delta":{"type":"signature_delta","signature":"abc"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":0}

            event: ping
            data: {"type": "ping"}

            event: content_block_start
            data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"{\\"t\\":\\"meta\\""}}

            event: content_block_delta
            data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"}\\n{\\"t\\":\\"fim\\"}"}}

            event: content_block_stop
            data: {"type":"content_block_stop","index":1}

            event: message_delta
            data: {"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":41}}

            event: message_stop
            data: {"type":"message_stop"}

            """;

    @Test
    void entregaSoOTextoERemontaARespostaComUso() throws Exception {
        List<String> deltas = new ArrayList<>();
        LeitorDeStreamAnthropic.Leitura leitura = new LeitorDeStreamAnthropic.Leitura();
        leitor.ler(new BufferedReader(new StringReader(STREAM)), deltas::add, leitura);

        assertEquals(List.of("{\"t\":\"meta\"", "}\n{\"t\":\"fim\"}"), deltas);
        assertTrue(leitura.terminou);
        JsonNode raiz = leitor.remontar(leitura);
        assertEquals("{\"t\":\"meta\"}\n{\"t\":\"fim\"}", raiz.path("content").path(0).path("text").stringValue());
        assertEquals("end_turn", raiz.path("stop_reason").stringValue());
        assertEquals(812, raiz.path("usage").path("input_tokens").asLong());
        assertEquals(41, raiz.path("usage").path("output_tokens").asLong());
    }

    @Test
    void erroNoMeioDoStreamViraErroDaApi() {
        String stream = """
                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"oi"}}

                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """;
        List<String> deltas = new ArrayList<>();
        LeitorDeStreamAnthropic.Leitura leitura = new LeitorDeStreamAnthropic.Leitura();
        ErroDaApiDoModelo erro = assertThrows(ErroDaApiDoModelo.class,
                () -> leitor.ler(new BufferedReader(new StringReader(stream)), deltas::add, leitura));
        assertEquals(529, erro.status());
        assertEquals(List.of("oi"), deltas);
    }
}
