package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

class AnotadorFreioTest {
    @TempDir Path pasta;
    @Test void pausaDescartaTrechoERespostaEmAndamentoMesmoDepoisDeRetomar() throws Exception {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.anotadorDeMemoriaAtivo()).thenReturn(true);
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso();
        var freio = new Freio(props, mock(MedidorDeCusto.class), mock(AvisosProativos.class), new FalasService(), json, relogio, pasta.resolve("freio.json"));
        var memoria = new MemoriaService(pasta.resolve("memoria"), pasta.resolve("exclusoes"), json, relogio, props.zona());
        var entrou = new CountDownLatch(1); var liberar = new CountDownLatch(1); var chamadas = new AtomicInteger();
        ProvedorDeModelo modelo = pedido -> {
            chamadas.incrementAndGet(); entrou.countDown();
            if (!liberar.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("prazo do teste");
            return new RespostaDoModelo(List.of(new Bloco.Texto("{\"candidatos\":[{\"categoria\":\"perfil\",\"assunto\":\"cidade\",\"conteudo\":\"Moro em Curitiba\"}]}")), RespostaDoModelo.MotivoDeParada.FIM, null);
        };
        var anotador = new AnotadorDeMemoria(memoria, modelo, json, props, relogio, null);
        anotador.configurarFreio(freio);
        try {
            for (int i = 0; i < 10; i++) anotador.registrarTurno(42, 42, "hud", "moro em Curitiba");
            assertTrue(entrou.await(3, TimeUnit.SECONDS));
            freio.pausar("hud");
            for (int i = 0; i < 12; i++) anotador.registrarTurno(42, 42, "hud", "não gravar durante pausa");
            freio.retomar("hud"); liberar.countDown();
            assertTrue(anotador.aguardar(Duration.ofSeconds(3)));
            assertTrue(memoria.assuntos(42).isEmpty()); assertEquals(1, chamadas.get());
            anotador.registrarTurno(42, 42, "hud", "uma conversa nova");
            assertEquals(1, chamadas.get(), "a pausa não deixa turnos acumulados para disparar depois");
        } finally { liberar.countDown(); anotador.fechar(); }
    }
}
