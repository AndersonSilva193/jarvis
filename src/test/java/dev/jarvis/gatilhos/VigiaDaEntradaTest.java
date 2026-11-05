package dev.jarvis.gatilhos;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.ComandoPc;
import dev.jarvis.pc.PcService;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.pc.ResultadoPc;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** A pasta entrada: linha de base calada, ID novo uma vez, e nada sem PC ou sem agente novo. */
class VigiaDaEntradaTest {
    @TempDir Path pasta;
    final JsonMapper json = JsonMapper.builder().build();
    final JarvisProperties props = mock(JarvisProperties.class);
    final RelogioFalso relogio = new RelogioFalso("2026-11-02T15:00:00Z");
    final PcService pc = mock(PcService.class);
    final MotorDeGatilhos motor = mock(MotorDeGatilhos.class);
    /** O que está na pasta entrada do PC agora: id e nome. */
    final List<String[]> naPasta = new ArrayList<>();
    final List<ObjectNode> pedidos = new ArrayList<>();
    boolean agenteAntigo;
    Gatilhos gatilhos;
    VigiaDaEntrada vigia;

    @BeforeEach void preparar() {
        when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        when(props.telegram()).thenReturn(new JarvisProperties.Telegram("t", 42));
        when(props.acoesDeSites()).thenReturn(Map.of());
        when(pc.online()).thenReturn(true);
        when(pc.enfileirar(any(ComandoPc.class))).thenAnswer(i -> {
            ComandoPc comando = i.getArgument(0);
            pedidos.add((ObjectNode) comando.dados());
            // A forma é a do CatalogoDeArquivos.listar do agente real: "arquivos", "temMais" e "pasta".
            ObjectNode dados = json.createObjectNode().put("total", naPasta.size()).put("pagina", 1)
                    .put("tamanhoPagina", 50).put("temMais", false);
            if (!agenteAntigo) dados.put("pasta", "entrada");
            var itens = dados.putArray("arquivos");
            for (String[] a : naPasta) itens.addObject().put("id", a[0]).put("nome", a[1]).put("formato", "md")
                    .put("tamanho", 10);
            return CompletableFuture.completedFuture(new ResultadoPc(comando.id(), true, "ok", null, null, false, dados));
        });
        gatilhos = new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json"));
        vigia = new VigiaDaEntrada(pc, gatilhos, motor, json, relogio);
    }

    void comRegra() throws Exception {
        gatilhos.criar(42, FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA,
                Filtro.ler(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, null, Set.of()), "te aviso", "x",
                RegraDeGatilho.ORIGEM_CONVERSA, "p");
    }

    @Test void linhaDeBaseNaoDisparaEIdNovoDisparaUmaVez() throws Exception {
        comRegra();
        naPasta.add(new String[] {"11111111-1111-1111-1111-111111111111", "velho.md"});
        assertEquals(0, vigia.conferir(), "o que já estava lá não é novo");
        assertEquals("entrada", pedidos.getFirst().path("pasta").asString());

        naPasta.add(new String[] {"22222222-2222-2222-2222-222222222222", "chegou.md"});
        assertEquals(1, vigia.conferir());
        var evento = ArgumentCaptor.forClass(EventoDeGatilho.class);
        verify(motor).aoEvento(evento.capture());
        assertEquals(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, evento.getValue().fonte());
        assertEquals("chegou.md", evento.getValue().dado("nome"));
        assertEquals("22222222-2222-2222-2222-222222222222", evento.getValue().dado("id"));

        assertEquals(0, vigia.conferir(), "o mesmo arquivo não dispara de novo");
        var depoisDoRestart = new VigiaDaEntrada(pc, new Gatilhos(props, json, relogio, pasta.resolve("gatilhos.json")),
                motor, json, relogio);
        assertEquals(0, depoisDoRestart.conferir(), "os vistos estão no disco");
    }

    @Test void semRegraOuSemPcNaoConsulta() throws Exception {
        assertEquals(0, vigia.conferir());
        comRegra();
        when(pc.online()).thenReturn(false);
        assertEquals(0, vigia.conferir());
        verify(pc, never()).enfileirar(any(ComandoPc.class));
    }

    @Test void agenteQueNaoConheceAPastaNaoViraArquivoNovo() throws Exception {
        comRegra();
        agenteAntigo = true;
        naPasta.add(new String[] {"33333333-3333-3333-3333-333333333333", "da_raiz.md"});
        assertEquals(0, vigia.conferir());
        assertFalse(gatilhos.entradaInicializada(), "resposta sem pasta não serve nem de linha de base");
        verifyNoInteractions(motor);
    }
}
