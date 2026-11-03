package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.agente.Freio;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.ia.provedor.*;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.*;
import tools.jackson.databind.json.JsonMapper;

class RevisorFreioTest {
    @TempDir Path pasta;
    @Test void revisorTambemDescartaResultadoAnteriorAPausaERetomada() throws Exception {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("UTC")); when(props.anotadorDeMemoriaAtivo()).thenReturn(true);
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso();
        var freio = new Freio(props, mock(MedidorDeCusto.class), mock(AvisosProativos.class), new FalasService(), json, relogio, pasta.resolve("freio.json"));
        var procedimentos = new ProcedimentosService(pasta.resolve("procedimentos"), json, relogio); var chamadas = new AtomicInteger();
        ProvedorDeModelo modelo = pedido -> {
            chamadas.incrementAndGet(); freio.pausar("hud"); freio.retomar("hud");
            return new RespostaDoModelo(List.of(new Bloco.Texto("{\"acoes\":[{\"nome\":\"editar-notas\",\"titulo\":\"Editar notas\",\"quando\":\"Ao editar notas\",\"corpo\":\"Leia primeiro.\"}]}")), null, null);
        };
        var revisor = new RevisorDeProcedimentos(procedimentos, modelo, json, props, relogio, null); revisor.configurarFreio(freio);
        List<Mensagem> turno = new ArrayList<>(); turno.add(Mensagem.texto(Mensagem.Papel.USUARIO, "edite a nota"));
        for (int i = 0; i < 5; i++) turno.add(new Mensagem(Mensagem.Papel.ASSISTENTE, List.of(new Bloco.Chamada("t" + i, "ler_arquivo", json.createObjectNode()))));
        try {
            revisor.registrarTurno(42, 42, "hud", turno);
            assertTrue(revisor.aguardar(Duration.ofSeconds(3))); assertEquals(1, chamadas.get());
            assertTrue(procedimentos.listar(42).isEmpty());
            freio.pausar("hud"); revisor.registrarTurno(42, 42, "hud", turno);
            assertEquals(1, chamadas.get());
        } finally { revisor.fechar(); }
    }
}
