package dev.jarvis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** A cota do Tavily vem crua do .env: valor torto vira o padrão em vez de impedir o Jarvis de subir. */
class JarvisPropertiesBuscaTest {

    private static JarvisProperties props(String cota) {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null,
                new JarvisProperties.Anthropic("chave", "claude-haiku-5-5", 1024, 512), null, null, null,
                null, null, new JarvisProperties.BuscaWeb(true, "web_search_20250305", 3, "tavily",
                        "tvly-x", "", cota), null, null);
    }

    @Test void cotaTortaCaiNoPadrao() {
        // O caso real: a linha do .env colada no comentário seguinte.
        assertEquals(1000, props("1000ArquivocomapersonalidadedoJarvis").cotaMensalDoTavily());
        assertEquals(1000, props("").cotaMensalDoTavily());
        assertEquals(1000, props(null).cotaMensalDoTavily());
    }

    @Test void cotaValidaVale() {
        assertEquals(500, props(" 500 ").cotaMensalDoTavily());
        assertEquals(0, props("0").cotaMensalDoTavily());
        assertEquals(0, props("-3").cotaMensalDoTavily());
    }
}
