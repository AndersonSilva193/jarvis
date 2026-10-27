package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LeiturasVisiveisTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "11111111-1111-1111-1111-111111111111";
    private static final String HASH = "a".repeat(64);

    private static ObjectNode pagina(int inicio, String conteudo, int total) {
        ObjectNode pagina = JSON.createObjectNode().put("id", ID).put("nome", "nota.md")
                .put("formato", "md").put("versaoEsperada", HASH).put("inicio", inicio)
                .put("totalCaracteres", total).put("conteudo", conteudo)
                .put("truncado", inicio + conteudo.length() < total);
        if (inicio + conteudo.length() < total)
            pagina.put("proximoInicio", inicio + conteudo.length());
        return pagina;
    }

    @Test void reescritaExigeTodosOsTrechosMostradosAoModelo() throws Exception {
        LeiturasVisiveis leituras = new LeiturasVisiveis();
        String arquivo = "x".repeat(15_000);
        Ferramenta ler = new Ferramenta() {
            @Override public String nome() { return "ler_arquivo"; }
            @Override public String descricao() { return "Lê arquivo"; }
            @Override public ObjectNode esquemaEntrada() { return JSON.createObjectNode().put("type", "object"); }
            @Override public String executar(JsonNode entrada) {
                int inicio = entrada.path("inicio").asInt(0);
                return pagina(inicio, arquivo.substring(inicio, Math.min(inicio + 6_000, arquivo.length())),
                        arquivo.length()).toString();
            }
        };
        JarvisProperties props = mock(JarvisProperties.class);
        when(props.ferramentasSempreCarregadas()).thenReturn(List.of("ler_arquivo"));
        when(props.resultadoMaxCaracteres()).thenReturn(6_000);
        Ferramentas registro = new Ferramentas(List.of(ler), JSON, evento -> { }, props, null, leituras);
        OuvinteProgresso contexto = new OuvinteProgresso() {
            @Override public long usuarioAutenticado() { return 42; }
        };

        ObjectNode uso = JSON.createObjectNode().put("name", "ler_arquivo").put("id", "tu-1");
        uso.set("input", JSON.createObjectNode().put("inicio", 0));
        JsonNode primeira = JSON.readTree(registro.executar(uso, contexto, r -> { })
                .path("content").stringValue());
        int proximo = primeira.path("proximoInicio").intValue();
        assertTrue(proximo > 0 && proximo < 6_000);
        assertFalse(leituras.integral(42, ID, HASH));
        assertFalse(leituras.integral(7, ID, HASH));

        uso.set("input", JSON.createObjectNode().put("inicio", proximo));
        JsonNode segunda = JSON.readTree(registro.executar(uso, contexto, r -> { })
                .path("content").stringValue());
        assertEquals(proximo, segunda.path("inicio").intValue());
        assertFalse(leituras.integral(42, ID, HASH));
        int ultimo = segunda.path("proximoInicio").intValue();
        uso.set("input", JSON.createObjectNode().put("inicio", ultimo));
        JsonNode terceira = JSON.readTree(registro.executar(uso, contexto, r -> { })
                .path("content").stringValue());
        assertEquals(arquivo.length(), terceira.path("inicio").intValue()
                + terceira.path("conteudo").stringValue().length());
        assertTrue(leituras.integral(42, ID, HASH));
    }
}
