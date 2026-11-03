package dev.jarvis.conversa;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import dev.jarvis.agente.*;
import dev.jarvis.computer.TarefasComputerUse;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.*;
import dev.jarvis.web.ConversaController;
import dev.jarvis.web.TokenHudFiltro;
import tools.jackson.databind.json.JsonMapper;

class FreioConversaTest {
    @TempDir Path pasta;
    @Test @SuppressWarnings("unchecked") void httpOcupadoAceitaFreioSemLockESemModeloERespostaAntigaNaoSobrescreve() throws Exception {
        var props = mock(JarvisProperties.class);
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("teste", 42));
        when(props.hud()).thenReturn(new JarvisProperties.Hud("token-teste", ""));
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        var freio = new Freio(props, mock(MedidorDeCusto.class), mock(AvisosProativos.class), new FalasService(), JsonMapper.builder().build(), new RelogioFalso(), pasta.resolve("freio.json"));
        var assistente = mock(Assistente.class); var tarefas = mock(TarefasComputerUse.class);
        ObjectProvider<Assistente> pa = mock(ObjectProvider.class); when(pa.getObject()).thenReturn(assistente);
        ObjectProvider<TarefasComputerUse> pt = mock(ObjectProvider.class); when(pt.getObject()).thenReturn(tarefas);
        var eventos = new java.util.ArrayList<Object>(); var registro = new RegistroConversas(eventos::add);
        var antigo = registro.reservar(42, "pedido lento");
        var conversa = new ConversaService(assistente, registro, props);
        conversa.configurarFreio(new ControleDoFreio(freio, pa, pt));
        try {
            var mvc = MockMvcBuilders.standaloneSetup(new ConversaController(conversa)).addFilters(new TokenHudFiltro(props)).build();
            mvc.perform(post("/api/conversa").contentType("application/json").content("{\"texto\":\"pausa tudo\"}"))
                    .andExpect(status().isUnauthorized());
            assertEquals(Freio.Estado.ATIVO, freio.retrato().estado());
            mvc.perform(post("/api/conversa").header("Authorization", "Bearer token-teste")
                    .contentType("application/json").content("{\"texto\":\"pausa tudo\"}"))
                    .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").isNotEmpty());
            assertEquals(ControleDoFreio.PAREI, registro.historico(42).ultima().texto());
            assertNull(registro.historico(42).emAndamento());
            verify(assistente).interromperPedidoAtual(42); verify(tarefas).cancelarUltima(42);
            registro.concluirSemHistorico(42, new RespostaConversa(antigo.id(), "hud", "Parei, senhor.", List.of(), null, "agora", null));
            assertEquals(ControleDoFreio.PAREI, registro.historico(42).ultima().texto());
            conversa.enviar("bom dia");
            verify(assistente, timeout(1000)).responder(eq(42L), eq("bom dia"), eq(Canal.HUD), any());
            conversa.enviar("retoma");
            assertEquals(Freio.Estado.ATIVO, freio.retrato().estado());
        } finally { conversa.fechar(); }
    }
}
