package dev.jarvis.ia.provedor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.ia.provedor.RespostaDoModelo.MotivoDeParada;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Formato neutro ↔ Messages API, sem rede. A régua de todos os casos é o {@code toString()}, e não
 * o {@code equals}: o {@code equals} do Jackson não olha a ordem das chaves, e a ordem é parte do
 * prefixo que o cache de prompt casa.
 */
class ConversorAnthropicTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConversorAnthropic conversor = new ConversorAnthropic(JSON);

    @Test
    @DisplayName("texto nascido em Java vai como string, e volta como o mesmo texto")
    void textoDoUsuarioVaiComoString() {
        Mensagem pedido = Mensagem.texto(Mensagem.Papel.USUARIO, "como está o servidor?");

        ObjectNode enviado = conversor.mensagem(pedido);

        assertEquals("{\"role\":\"user\",\"content\":\"como está o servidor?\"}", enviado.toString());
        assertEquals(pedido, conversor.mensagemDe(enviado));
    }

    @Test
    @DisplayName("texto do modelo volta como array, com as citations da busca intactas")
    void textoComCitacoesVoltaIgual() {
        ArrayNode conteudo = JSON.createArrayNode();
        ObjectNode texto = conteudo.addObject().put("type", "text").put("text", "É o Eduardo.");
        texto.putArray("citations").addObject().put("type", "web_search_result_location")
                .put("url", "https://exemplo.com").put("encrypted_index", "Eo8BCioIAhgB");

        List<Bloco> blocos = conversor.blocosDe(conteudo);

        Bloco.Texto neutro = assertInstanceOf(Bloco.Texto.class, blocos.getFirst());
        assertEquals("É o Eduardo.", neutro.texto(), "o texto vale para qualquer provedor");
        ObjectNode devolvido = conversor.mensagem(new Mensagem(Mensagem.Papel.ASSISTENTE, blocos));
        assertEquals(conteudo.toString(), devolvido.path("content").toString());
    }

    @Test
    @DisplayName("chamada de ferramenta: id, nome e entrada, nos dois sentidos e na mesma ordem")
    void chamadaDeFerramenta() {
        ArrayNode conteudo = JSON.createArrayNode();
        conteudo.addObject().put("type", "tool_use").put("id", "toolu_01").put("name", "clima")
                .putObject("input").put("cidade", "Curitiba");

        Bloco.Chamada chamada = assertInstanceOf(Bloco.Chamada.class,
                conversor.blocosDe(conteudo).getFirst());

        assertEquals("toolu_01", chamada.id());
        assertEquals("clima", chamada.nome());
        assertEquals("Curitiba", chamada.entrada().path("cidade").stringValue());
        assertEquals(conteudo.get(0).toString(), conversor.bloco(chamada).toString());
        // A chamada que o Java monta (o modo repouso) sai no mesmo molde que a API devolve.
        Bloco.Chamada nossa = new Bloco.Chamada("repouso_1", "controlar_pc",
                JSON.createObjectNode().put("acao", "modo_repouso"));
        assertEquals("{\"type\":\"tool_use\",\"id\":\"repouso_1\",\"name\":\"controlar_pc\","
                + "\"input\":{\"acao\":\"modo_repouso\"}}", conversor.bloco(nossa).toString());
    }

    @Test
    @DisplayName("resultado com erro leva is_error; sem erro, a chave nem aparece")
    void resultadoComErro() {
        Bloco.Resultado falhou = new Bloco.Resultado("toolu_01", "Erro ao executar clima: timeout", true);
        Bloco.Resultado deuCerto = new Bloco.Resultado("toolu_02", "22 °C", false);

        JsonNode comErro = conversor.bloco(falhou);
        JsonNode semErro = conversor.bloco(deuCerto);

        assertEquals("{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_01\","
                + "\"content\":\"Erro ao executar clima: timeout\",\"is_error\":true}", comErro.toString());
        assertEquals("{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_02\",\"content\":\"22 °C\"}",
                semErro.toString());
        ArrayNode ida = JSON.createArrayNode().add(comErro).add(semErro);
        assertEquals(List.of(falhou, deuCerto), conversor.blocosDe(ida));
    }

    @Test
    @DisplayName("pensamento, busca na web e busca de ferramentas voltam byte a byte")
    void opacosPreservados() {
        ArrayNode conteudo = JSON.createArrayNode();
        conteudo.addObject().put("type", "thinking").put("thinking", "deixa eu ver")
                .put("signature", "EqoBCkgIARABGAIiQ");
        conteudo.addObject().put("type", "server_tool_use").put("id", "srvtoolu_1")
                .put("name", "web_search").putObject("input").put("query", "prefeito de Curitiba");
        ObjectNode achado = conteudo.addObject().put("type", "web_search_tool_result")
                .put("tool_use_id", "srvtoolu_1");
        achado.putArray("content").addObject().put("type", "web_search_result")
                .put("url", "https://exemplo.com").put("encrypted_content", "EqgfCioIARgBIiQ3");
        conteudo.addObject().put("type", "server_tool_use").put("id", "srvtoolu_2")
                .put("name", "tool_search_tool_bm25").putObject("input").put("query", "ler pdf");
        conteudo.addObject().put("type", "tool_search_tool_result").put("tool_use_id", "srvtoolu_2")
                .putObject("content").put("type", "tool_search_tool_search_result")
                .putArray("tool_references").addObject().put("type", "tool_reference")
                .put("tool_name", "ler_arquivo");

        List<Bloco> blocos = conversor.blocosDe(conteudo);

        assertTrue(blocos.stream().allMatch(Bloco.Opaco.class::isInstance), "nada disso é neutro");
        Bloco.Opaco pensamento = (Bloco.Opaco) blocos.get(0);
        assertTrue(pensamento.soNoTurno(), "o pensamento vale só no turno em que nasceu");
        assertEquals("prefeito de Curitiba", ((Bloco.Opaco) blocos.get(1)).consultaWeb());
        assertEquals("", ((Bloco.Opaco) blocos.get(3)).consultaWeb(),
                "a busca de ferramentas não é busca na web");
        assertFalse(((Bloco.Opaco) blocos.get(2)).soNoTurno());
        ObjectNode devolvido = conversor.mensagem(new Mensagem(Mensagem.Papel.ASSISTENTE, blocos));
        assertEquals(conteudo.toString(), devolvido.path("content").toString());
    }

    @Test
    @DisplayName("opaco de outro provedor não vai à Anthropic")
    void opacoDeOutroProvedorNaoVai() {
        Bloco.Opaco alheio = Bloco.Opaco.de("local", JSON.createObjectNode().put("type", "raciocinio"));

        assertNull(conversor.bloco(alheio));
        ObjectNode so = conversor.mensagem(new Mensagem(Mensagem.Papel.ASSISTENTE, List.of(alheio)));
        assertEquals("(sem resposta)", so.path("content").stringValue(),
                "mensagem vazia a API recusa");
        Bloco.Texto comOriginalAlheio = new Bloco.Texto("oi", alheio);
        assertEquals("{\"type\":\"text\",\"text\":\"oi\"}", conversor.bloco(comOriginalAlheio).toString(),
                "o texto vale para todos; o original, só para quem o produziu");
    }

    @Test
    @DisplayName("o system lido volta como o mesmo objeto, e remontado sai igual")
    void sistemaComCache() {
        ArrayNode blocos = JSON.createArrayNode();
        blocos.addObject().put("type", "text").put("text", "personalidade")
                .putObject("cache_control").put("type", "ephemeral").put("ttl", "1h");
        blocos.addObject().put("type", "text").put("text", "agora são 9h");

        InstrucoesDeSistema instrucoes = ConversorAnthropic.instrucoesDe(blocos);

        assertEquals(List.of(new BlocoDeSistema("personalidade", "1h"),
                new BlocoDeSistema("agora são 9h", null)), instrucoes.blocos());
        assertSame(blocos, instrucoes.renderizada(ConversorAnthropic.PROVEDOR, conversor::sistema),
                "a Anthropic recebe de volta exatamente o que entregou");
        assertEquals(blocos.toString(), conversor.sistema(new InstrucoesDeSistema(instrucoes.blocos()))
                .toString());
        // 5m é o padrão da API: o campo ttl não sai, como no PromptDeSistema.
        assertEquals("[{\"type\":\"text\",\"text\":\"p\",\"cache_control\":{\"type\":\"ephemeral\"}}]",
                conversor.sistema(InstrucoesDeSistema.de(new BlocoDeSistema("p", "5m"))).toString());
    }

    @Test
    @DisplayName("ferramentas: definições, depois a busca de ferramentas, depois a busca na web")
    void ferramentasNaOrdemDeAntes() {
        ObjectNode esquema = JSON.createObjectNode().put("type", "object");
        ObjectNode buscaWeb = JSON.createObjectNode().put("type", "web_search_20250305")
                .put("name", "web_search").put("max_uses", 3);
        ListaDeFerramentas lista = new ListaDeFerramentas(List.of(
                new DefinicaoDeFerramenta("clima", "Previsão do tempo", esquema, false),
                new DefinicaoDeFerramenta("ler_arquivo", "Lê um arquivo", esquema, true)),
                true, List.of(Bloco.Opaco.de(ConversorAnthropic.PROVEDOR, buscaWeb),
                        Bloco.Opaco.de("local", JSON.createObjectNode())));

        ArrayNode renderizada = lista.renderizada(ConversorAnthropic.PROVEDOR, conversor::ferramentas);

        assertEquals("[{\"name\":\"clima\",\"description\":\"Previsão do tempo\","
                + "\"input_schema\":{\"type\":\"object\"}},"
                + "{\"name\":\"ler_arquivo\",\"description\":\"Lê um arquivo\","
                + "\"input_schema\":{\"type\":\"object\"},\"defer_loading\":true},"
                + "{\"type\":\"tool_search_tool_bm25_20251119\",\"name\":\"tool_search_tool_bm25\"},"
                + buscaWeb + "]", renderizada.toString());
        assertSame(renderizada, lista.renderizada(ConversorAnthropic.PROVEDOR, conversor::ferramentas),
                "montada uma vez por pedido: o mesmo objeto em todos os passos");
        assertTrue(conversor.ferramentas(ListaDeFerramentas.vazia()).isEmpty());
    }

    @Test
    @DisplayName("a resposta: blocos, motivo de parada e uso, com as buscas na web")
    void resposta() {
        ObjectNode raiz = JSON.createObjectNode().put("stop_reason", "tool_use");
        raiz.putArray("content").addObject().put("type", "text").put("text", "Vou ver.");
        raiz.putObject("usage").put("input_tokens", 10).put("output_tokens", 5)
                .put("cache_creation_input_tokens", 3).put("cache_read_input_tokens", 7)
                .putObject("server_tool_use").put("web_search_requests", 2);

        RespostaDoModelo resposta = conversor.resposta(raiz);

        assertEquals(MotivoDeParada.FERRAMENTA, resposta.motivo());
        assertEquals(new RespostaDoModelo.Uso(10, 5, 3, 7, 2), resposta.uso());
        assertEquals(List.of("Vou ver."), resposta.textos());
        assertEquals(MotivoDeParada.PAUSA, ConversorAnthropic.motivoDe("pause_turn"));
        assertEquals(MotivoDeParada.FIM, ConversorAnthropic.motivoDe("end_turn"));
        assertEquals(MotivoDeParada.LIMITE_DE_TOKENS, ConversorAnthropic.motivoDe("max_tokens"));
        assertEquals(MotivoDeParada.OUTRO, ConversorAnthropic.motivoDe(""));
    }
}
