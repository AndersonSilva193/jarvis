package dev.jarvis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Como SITES_MONITORADOS é lido: nome repetido, entrada mal formada e lista vazia. */
class JarvisPropertiesSitesTest {

    private static Map<String, String> sites(String config) {
        return new JarvisProperties(null, null, config, null, null, null, null, null, null, null,
                null, null, null).sitesMonitorados();
    }

    @Test
    void listaVaziaOuNulaNaoRendeNada() {
        assertTrue(sites(null).isEmpty());
        assertTrue(sites("").isEmpty());
        assertTrue(sites("   ").isEmpty());
        assertTrue(sites(",,").isEmpty());
    }

    @Test
    void mantemAOrdemEOsDoisSitesDoExemplo() {
        Map<String, String> mapa = sites("agendabarber-site=https://api.barberagendaonline.com,"
                + "agendabarber-health=https://api.barberagendaonline.com/actuator/health");

        assertEquals(List.of("agendabarber-site", "agendabarber-health"), List.copyOf(mapa.keySet()));
        assertEquals("https://api.barberagendaonline.com", mapa.get("agendabarber-site"));
        assertEquals("https://api.barberagendaonline.com/actuator/health",
                mapa.get("agendabarber-health"));
    }

    @Test
    void nomeRepetidoMantemAPrimeiraUrl() {
        Map<String, String> mapa = sites("api=https://um.exemplo.com,api=https://dois.exemplo.com");

        assertEquals(1, mapa.size());
        assertEquals("https://um.exemplo.com", mapa.get("api"));
    }

    @Test
    void espacosEmVoltaSaoAparados() {
        Map<String, String> mapa = sites("  api  =  https://um.exemplo.com  ,  "
                + "health = https://dois.exemplo.com ");

        assertEquals(Map.of("api", "https://um.exemplo.com", "health", "https://dois.exemplo.com"),
                mapa);
    }

    @Test
    void entradasMalFormadasSaoIgnoradas() {
        // Sem "=", nome vazio e esquemas que não são http/https.
        assertTrue(sites("https://sem-nome.exemplo.com").isEmpty());
        assertTrue(sites("=https://sem-nome.exemplo.com").isEmpty());
        assertTrue(sites("vazio=").isEmpty());
        assertTrue(sites("arquivo=file:///etc/passwd").isEmpty());
        assertTrue(sites("script=javascript:alert(1)").isEmpty());
        assertTrue(sites("semEsquema=api.exemplo.com").isEmpty());

        // Uma entrada ruim no meio não derruba as boas.
        Map<String, String> mapa = sites("ok=https://um.exemplo.com,ruim,outro=https://dois.exemplo.com");
        assertEquals(List.of("ok", "outro"), List.copyOf(mapa.keySet()));
    }

    @Test
    void urlComQueryStringNaoVaiInteiraParaOLog() {
        assertEquals("https://api.exemplo.com/health?...",
                JarvisProperties.semQuery("https://api.exemplo.com/health?token=segredo"));
        assertEquals("https://api.exemplo.com/health",
                JarvisProperties.semQuery("https://api.exemplo.com/health"));
    }

    @Test
    void urlComQueryStringContinuaValendoNoMapa() {
        Map<String, String> mapa = sites("api=https://api.exemplo.com/health?verbose=1");

        assertEquals("https://api.exemplo.com/health?verbose=1", mapa.get("api"));
    }
}
