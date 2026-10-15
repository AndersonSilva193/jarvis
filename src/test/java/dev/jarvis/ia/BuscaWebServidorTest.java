package dev.jarvis.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.config.JarvisProperties;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A definição que vai no array de ferramentas: é o contrato com a Messages API. */
class BuscaWebServidorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JarvisProperties props(JarvisProperties.BuscaWeb busca) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512),
                new JarvisProperties.Clima("Curitiba", -25.4284, -49.2733),
                null, null, null, null, busca, null);
    }

    @Test
    @DisplayName("desligada não produz definição nenhuma")
    void desligada() {
        assertFalse(new BuscaWebServidor(props(new JarvisProperties.BuscaWeb(false, "web_search_20250305", 3)),
                JSON).ativa());
        assertNull(new BuscaWebServidor(props(null), JSON).definicao(),
                "sem o bloco de config a busca fica desligada, não estoura");
        assertFalse(BuscaWebServidor.desligada().ativa());
    }

    @Test
    @DisplayName("ligada manda tipo, nome, teto e a localização do usuário")
    void ligada() {
        BuscaWebServidor busca = new BuscaWebServidor(
                props(new JarvisProperties.BuscaWeb(true, "web_search_20250305", 3)), JSON);

        assertTrue(busca.ativa());
        assertEquals(3, busca.maxUsos());

        ObjectNode def = busca.definicao();
        assertEquals("web_search_20250305", def.path("type").stringValue());
        assertEquals("web_search", def.path("name").stringValue(), "o nome é fixo na API");
        assertEquals(3, def.path("max_uses").intValue());
        assertEquals("approximate", def.path("user_location").path("type").stringValue());
        assertEquals("Curitiba", def.path("user_location").path("city").stringValue());
        assertEquals("BR", def.path("user_location").path("country").stringValue());
        assertEquals("America/Sao_Paulo", def.path("user_location").path("timezone").stringValue());
    }

    @Test
    @DisplayName("a versão do tipo é configuração, para trocar de modelo sem mexer no código")
    void tipoConfiguravel() {
        BuscaWebServidor busca = new BuscaWebServidor(
                props(new JarvisProperties.BuscaWeb(true, "web_search_20260318", 5)), JSON);

        assertEquals("web_search_20260318", busca.definicao().path("type").stringValue());
        assertEquals(5, busca.definicao().path("max_uses").intValue());
    }
}
