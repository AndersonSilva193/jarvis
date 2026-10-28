package dev.jarvis.briefing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.briefing.BriefingService.Secao;
import dev.jarvis.ia.Canal;
import dev.jarvis.ia.SistemaDoJarvis;
import dev.jarvis.ia.provedor.BancadaLocal;
import dev.jarvis.ia.provedor.Bloco;
import dev.jarvis.ia.provedor.ProvedorDeApoio;
import dev.jarvis.ia.provedor.ProvedorDeModelo;
import dev.jarvis.ia.provedor.RespostaDoModelo;
import tools.jackson.databind.node.ArrayNode;

/** As marcas do briefing reprovam o modelo do PC do mesmo jeito que já reprovavam o Claude. */
class NarradorLocalTest {

    private static final List<Secao> SECOES = List.of(new Secao("clima", "Curitiba: 18 °C"));
    private static final String CERTO = "[0] Seu briefing está pronto.\n[1] Faz 18 graus em Curitiba.\n[2] Dia tranquilo.";

    @TempDir Path pasta;
    private BancadaLocal bancada;
    private final AtomicInteger chamadasAoClaude = new AtomicInteger();
    private final ProvedorDeModelo claude = pedido -> {
        chamadasAoClaude.incrementAndGet();
        return new RespostaDoModelo(List.of(new Bloco.Texto(CERTO)), null, null);
    };

    @AfterEach
    void fechar() throws Exception {
        if (bancada != null) bancada.close();
    }

    private NarradorDeBriefing narrador() {
        SistemaDoJarvis sistema = mock(SistemaDoJarvis.class);
        ArrayNode blocos = BancadaLocal.JSON.createArrayNode();
        blocos.addObject().put("type", "text").put("text", "personalidade");
        when(sistema.blocos(any(Canal.class))).thenReturn(blocos);
        return new NarradorDeBriefing(bancada.apoio(claude), sistema);
    }

    @Test
    @DisplayName("marcas erradas no local: o Claude narra")
    void marcasErradasVaoAoClaude() {
        bancada = new BancadaLocal(pasta).responde("[0] Seu briefing está pronto.\n[1] Faz 18 graus.");

        assertEquals(List.of("Seu briefing está pronto.", "Faz 18 graus em Curitiba.", "Dia tranquilo."),
                narrador().narrar("Anderson", SECOES));
        assertEquals(1, chamadasAoClaude.get());
        assertEquals(1L, bancada.medidor.localDeHoje().reservas().get(ProvedorDeApoio.REPROVADO));
    }

    @Test
    @DisplayName("marcas certas no local: fica o local")
    void marcasCertasFicam() {
        bancada = new BancadaLocal(pasta).responde(CERTO);
        assertEquals(3, narrador().narrar("Anderson", SECOES).size());
        assertEquals(0, chamadasAoClaude.get());
    }
}
