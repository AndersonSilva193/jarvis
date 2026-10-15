package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.*;
import dev.jarvis.ferramentas.ResultadoFerramenta.Artefato;
import dev.jarvis.briefing.BriefingService;

class ConversaHttpTest {
    @TempDir Path pasta;
    private final JarvisProperties props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
            new JarvisProperties.Telegram("teste", 42), null, null, null,
            new JarvisProperties.Hud("token-de-teste", ""), null, null, null, null, null);

    @Test void autenticaRotasEValidaPedidoPeloHttp() throws Exception {
        var conversa = mock(ConversaService.class);
        when(conversa.enviar("oi")).thenReturn("pedido");
        when(conversa.historico()).thenReturn(new RegistroConversas.Historico("sessao", 0, List.of(), null, null));
        var mvc = MockMvcBuilders.standaloneSetup(new ConversaController(conversa))
                .addFilters(new TokenHudFiltro(props)).build();
        mvc.perform(post("/api/conversa").contentType("application/json").content("{\"texto\":\"oi\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/conversa").param("token", "token-de-teste")
                .contentType("application/json").content("{\"texto\":\"oi\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/conversa").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\" oi \"}"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").value("pedido"));
        mvc.perform(post("/api/conversa").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/conversa/ultima").header("Authorization", "Bearer token-de-teste"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/conversa/historico").param("token", "token-de-teste"))
                .andExpect(status().isUnauthorized());
        when(conversa.enviar("oi")).thenThrow(new RegistroConversas.OcupadaException());
        mvc.perform(post("/api/conversa").header("Authorization", "Bearer token-de-teste")
                .contentType("application/json").content("{\"texto\":\"oi\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/conversa/limpar").header("Authorization", "Bearer token-de-teste"))
                .andExpect(status().isNoContent());
        verify(conversa).limpar();
    }

    @Test void sseNovoEAliasRecebemMesmoEventoSemExporOutrosChats() throws Exception {
        var eventos = new EventosHud(props);
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new EventosController(eventos),
                    new BriefingController(mock(BriefingService.class), eventos))
                    .addFilters(new TokenHudFiltro(props)).build();
            var novo = mvc.perform(get("/api/eventos").param("token", "token-de-teste"))
                    .andExpect(request().asyncStarted()).andReturn();
            var antigo = mvc.perform(get("/api/briefing/eventos").param("token", "token-de-teste"))
                    .andExpect(request().asyncStarted()).andReturn();
            assertEquals(2, eventos.conexoesAbertas());
            eventos.aoConversar(new EventoConversa(99, "conversa.resposta", "segredo"));
            var resposta = new RespostaConversa("id", "hud", "Olá", List.of(), null, "2026-10-14T10:00:00Z", null);
            eventos.aoConversar(new EventoConversa(42, "conversa.resposta", resposta));
            assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                while (!antigo.getResponse().getContentAsString().contains("conversa.resposta")) Thread.sleep(5);
            });
            assertTrue(novo.getResponse().getContentAsString().contains("\"id\":\"id\""));
            assertFalse(novo.getResponse().getContentAsString().contains("segredo"));
            mvc.perform(get("/api/eventos").header("Authorization", "Bearer errado").param("token", "token-de-teste"))
                    .andExpect(status().isUnauthorized());
        } finally { eventos.fechar(); }
    }

    @Test void anexoExigeTokenEEntreguaSomenteArquivoRegistrado() throws Exception {
        var anexos = new AnexosService();
        try {
            var anexo = anexos.registrar(new Artefato(Files.writeString(pasta.resolve("texto.txt"), "conteúdo"), "texto.txt", "text/plain"));
            var mvc = MockMvcBuilders.standaloneSetup(new AnexosController(anexos))
                    .addFilters(new TokenHudFiltro(props)).build();
            mvc.perform(get("/api/anexos/" + anexo.id())).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/anexos/" + anexo.id()).param("token", "token-de-teste"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"));
            mvc.perform(get("/api/anexos/" + java.util.UUID.randomUUID()).header("Authorization", "Bearer token-de-teste"))
                    .andExpect(status().isNotFound());
        } finally { anexos.fechar(); }
    }
}
