package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

/** As buscas por provedor: gravadas no dia, somadas no mês, sem atravessar a virada. */
class MedidorDeCustoBuscaTest {

    @TempDir
    Path pasta;

    private final List<MedidorDeCusto> criados = new ArrayList<>();

    /** Fecha o executor de gravação: sem isso o @TempDir não consegue ser apagado no Windows. */
    @AfterEach
    void fechar() {
        criados.forEach(MedidorDeCusto::fechar);
    }

    private MedidorDeCusto subir(RelogioFalso relogio) {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 42L), null, null, null, null, null, null,
                null, null, null, null, null, null, null, null,
                new JarvisProperties.Custo(null, "modelo-a=1/5/1.25/0.1"));
        var novo = new MedidorDeCusto(props, mock(TelegramClient.class), JsonMapper.builder().build(),
                relogio, pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    @DisplayName("o mês soma os dias gravados e sobrevive ao restart")
    void somaDoMes() throws Exception {
        var relogio = new RelogioFalso("2026-10-20T15:00:00Z");
        var antes = subir(relogio);
        antes.registrarBusca("tavily");
        antes.registrarBusca("tavily");
        antes.registrarBusca("searxng");
        relogio.avancar(Duration.ofDays(1));
        antes.registrarBusca("tavily");
        antes.aguardar();
        assertTrue(Files.readString(pasta.resolve("2026-10-20.json")).contains("\"buscas\""));

        var depois = subir(relogio);
        assertEquals(3, depois.buscasDoMes("tavily"));
        assertEquals(1, depois.buscasDoMes("searxng"));
        depois.registrarBusca("tavily");
        assertEquals(4, depois.buscasDoMes("tavily"), "hoje entra na hora, sem esperar o disco");
        assertEquals(0, depois.buscasDoMes("anthropic"));
    }

    @Test
    @DisplayName("na virada do mês a conta recomeça")
    void viradaDoMes() {
        // 31/10 às 12h em São Paulo; um dia depois é 1º de novembro.
        var relogio = new RelogioFalso("2026-10-31T15:00:00Z");
        var medidor = subir(relogio);
        medidor.registrarBusca("tavily");
        assertEquals(1, medidor.buscasDoMes("tavily"));
        relogio.avancar(Duration.ofDays(1));
        assertEquals(0, medidor.buscasDoMes("tavily"));
        medidor.registrarBusca("tavily");
        assertEquals(1, medidor.buscasDoMes("tavily"));
    }

    @Test
    @DisplayName("nome de provedor fora do formato vira 'outro'")
    void nomeTorto() {
        var medidor = subir(new RelogioFalso("2026-10-20T15:00:00Z"));
        medidor.registrarBusca("Tavily!");
        assertEquals(1, medidor.buscasDoMes("outro"));
    }
}
