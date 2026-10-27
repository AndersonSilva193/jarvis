package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.ia.Canal;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class FerramentasEconomiaTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private record Falsa(String nome, String texto, boolean falha) implements Ferramenta {
        @Override public String descricao() { return "descricao"; }
        @Override public ObjectNode esquemaEntrada() {
            return JSON.createObjectNode().put("type", "object");
        }
        @Override public String executar(JsonNode entrada) {
            if (falha) throw new IllegalStateException("falhou");
            return texto;
        }
    }

    private Ferramentas registro(MedidorDeCusto medidor, Ferramenta... ferramentas) {
        JarvisProperties props = mock(JarvisProperties.class);
        when(props.ferramentasSempreCarregadas()).thenReturn(List.of("clima"));
        when(props.resultadoMaxCaracteres()).thenReturn(6_000);
        return new Ferramentas(List.of(ferramentas), JSON, evento -> { }, props, medidor);
    }

    private static JsonNode porNome(ArrayNode definicoes, String nome) {
        for (JsonNode def : definicoes) {
            if (nome.equals(def.path("name").stringValue())) return def;
        }
        fail("Definição ausente: " + nome);
        return null;
    }

    @Test void somenteFerramentasForaDaListaSaoAdiadas() {
        Ferramentas registro = registro(null, new Falsa("clima", "ok", false),
                new Falsa("ler_arquivo", "ok", false),
                new Falsa("tarefa_no_pc", "ok", false),
                new Falsa("ouvir_resposta", "ok", false));
        ArrayNode hud = registro.definicoes(Canal.HUD);
        assertTrue(porNome(hud, "ler_arquivo").path("defer_loading").booleanValue());
        for (String nome : List.of("clima", "tarefa_no_pc", "ouvir_resposta")) {
            assertTrue(porNome(hud, nome).path("defer_loading").isMissingNode(), nome);
            assertTrue(porNome(hud, nome).path("cache_control").isMissingNode(), nome);
        }
        assertTrue(porNome(registro.definicoes(Canal.TELEGRAM), "ler_arquivo")
                .path("defer_loading").booleanValue());
        assertFalse(registro.definicoes(Canal.TELEGRAM).toString().contains("ouvir_resposta"));
        assertFalse(hud.toString().contains("\"grupo\""));
        assertFalse(hud.toString().contains("\"simples\""));
    }

    @Test void contaUmaTentativaConhecidaMesmoSeFalhar() {
        MedidorDeCusto medidor = mock(MedidorDeCusto.class);
        Ferramentas registro = registro(medidor, new Falsa("clima", "ok", false),
                new Falsa("noticias", "", true));
        for (String nome : List.of("clima", "noticias", "desconhecida")) {
            ObjectNode uso = JSON.createObjectNode().put("name", nome).put("id", "tu-" + nome);
            uso.set("input", JSON.createObjectNode());
            registro.executar(uso);
        }
        verify(medidor).registrarFerramenta("clima");
        verify(medidor).registrarFerramenta("noticias");
        verify(medidor, times(2)).registrarFerramenta(anyString());
    }

    @Test void limitaSoTextoDoModeloEConservaResultadoIntegral() throws Exception {
        String longo = "abc".repeat(3_000);
        Ferramentas registro = registro(null, new Falsa("noticias", longo, false));
        AtomicReference<ResultadoFerramenta> integral = new AtomicReference<>();
        ObjectNode uso = JSON.createObjectNode().put("name", "noticias").put("id", "tu-1");
        uso.set("input", JSON.createObjectNode());
        ObjectNode resposta = registro.executar(uso, new dev.jarvis.conversa.OuvinteProgresso() { },
                integral::set);
        assertEquals(longo, integral.get().texto());
        String curto = resposta.path("content").stringValue();
        assertEquals(6_000, curto.length());
        assertTrue(curto.endsWith(LimiteDeResultado.AVISO));
    }

    @Test void jsonLongoContinuaValidoEComDadosDeContinuacao() throws Exception {
        ObjectNode original = JSON.createObjectNode();
        original.put("id", "arquivo-123");
        original.put("proximoInicio", 6000);
        original.put("conteudo", "x".repeat(9_000));
        String completo = original.toString();
        String curto = LimiteDeResultado.paraModelo(completo, 6_000, JSON);
        JsonNode resumo = JSON.readTree(curto);
        assertTrue(curto.length() <= 6_000);
        assertEquals("arquivo-123", resumo.path("id").stringValue());
        assertEquals(6000, resumo.path("proximoInicio").intValue());
        assertEquals(LimiteDeResultado.AVISO, resumo.path("aviso").stringValue());
        assertEquals(9_000, original.path("conteudo").stringValue().length());
    }

    @Test void jsonComListaMantemItensCompletos() throws Exception {
        ObjectNode original = JSON.createObjectNode();
        original.put("proximaPagina", 3);
        ArrayNode itens = original.putArray("itens");
        for (int i = 0; i < 8; i++) {
            itens.addObject().put("id", "item-" + i).put("texto", "x".repeat(1_000));
        }
        String curto = LimiteDeResultado.paraModelo(original.toString(), 6_000, JSON);
        JsonNode resumo = JSON.readTree(curto);
        assertTrue(curto.length() <= 6_000);
        assertEquals(3, resumo.path("proximaPagina").intValue());
        assertEquals(LimiteDeResultado.AVISO, resumo.path("aviso").stringValue());
        assertTrue(resumo.path("itens").size() < 8);
        for (JsonNode item : resumo.path("itens")) {
            assertEquals(1_000, item.path("texto").stringValue().length());
        }
    }

    @Test void jsonStringLongoTambemViraResumoValido() throws Exception {
        String valor = JSON.createObjectNode().put("v", "x".repeat(7_000)).path("v").toString();
        String curto = LimiteDeResultado.paraModelo(valor, 6_000, JSON);
        assertTrue(curto.length() <= 6_000);
        assertEquals(LimiteDeResultado.AVISO, JSON.readTree(curto).path("aviso").stringValue());
    }
}
