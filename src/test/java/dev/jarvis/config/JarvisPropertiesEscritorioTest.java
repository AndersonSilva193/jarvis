package dev.jarvis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/** Os nomes padrão das planilhas dos agentes na pasta do Jarvis no PC. */
class JarvisPropertiesEscritorioTest {

    @Test
    void agendaELeadsPadraoSaoCsv() {
        JarvisProperties props = new JarvisProperties(null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        assertEquals("agenda.csv", props.arquivoDaAgenda());
        assertEquals("leads.csv", props.arquivoDeLeads());
    }
}
