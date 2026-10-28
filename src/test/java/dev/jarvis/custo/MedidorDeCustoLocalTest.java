package dev.jarvis.custo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** As chamadas ao modelo do PC: tokens sem dólar, num balde que não mexe na conta do Claude. */
class MedidorDeCustoLocalTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir Path pasta;
    private final RelogioFalso relogio = new RelogioFalso();
    private final List<MedidorDeCusto> criados = new ArrayList<>();

    @AfterEach
    void fechar() {
        criados.forEach(MedidorDeCusto::fechar);
    }

    private MedidorDeCusto medidor() {
        var novo = new MedidorDeCusto(MedidorDeCustoCalculoTest.props("modelo-a=1/5/1.25/0.1", ""),
                mock(TelegramClient.class), JSON, relogio, pasta);
        criados.add(novo);
        return novo;
    }

    @Test
    @DisplayName("local soma tokens e chamadas, custo zero, fora do relatório e da taxa de cache")
    void localNaoMexeNoClaude() {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 100_000, 0, 100_000, 800_000, 0);
        medidor.registrarLocal(Origem.ANOTADOR, 5_000, 300);
        medidor.registrarLocal(Origem.ANOTADOR, 1_000, 100);
        medidor.registrarLocal(Origem.CLASSIFICADOR, 200, 10);

        var relatorio = medidor.hoje();
        assertEquals(80, relatorio.percentualLidoDoCache(), "o local não dilui o cache do Claude");
        assertEquals(List.of("modelo-a"), List.copyOf(relatorio.porModelo().keySet()));
        assertFalse(relatorio.porOrigem().containsKey(Origem.ANOTADOR), "custo do local não é linha de dólar");

        var local = medidor.localDeHoje();
        assertEquals(2L, local.chamadas().get(Origem.ANOTADOR));
        assertEquals(1L, local.chamadas().get(Origem.CLASSIFICADOR));
        assertEquals(3, local.totalDeChamadas());
        assertEquals(6_200, local.entrada());
        assertEquals(410, local.saida());
    }

    @Test
    @DisplayName("reservas por motivo e erros de formato vão ao JSON do dia e voltam no restart")
    void sobreviveAoRestart() throws Exception {
        var medidor = medidor();
        medidor.registrarLocal(Origem.BRIEFING, 900, 200);
        medidor.registrarReserva("pc_offline");
        medidor.registrarReserva("pc_offline");
        medidor.registrarReserva("reprovado");
        medidor.registrarReserva("Texto Livre Do Modelo");
        medidor.registrarErroDeFormato();
        medidor.aguardar();

        Path arquivo;
        try (var dias = Files.list(pasta)) {
            arquivo = dias.filter(p -> p.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
        }
        JsonNode dia = JSON.readTree(Files.readString(arquivo));
        assertEquals("local", dia.path("local").path("provedor").asString());
        assertEquals(0.0, dia.path("local").path("porOrigem").path("briefing").path("custoUsd").asDouble());
        assertEquals(2, dia.path("reservas").path("pc_offline").asInt());
        assertEquals(1, dia.path("reservas").path("outro").asInt(), "motivo fora do formato vira 'outro'");
        assertEquals(1, dia.path("errosDeFormato").asInt());

        var depois = medidor().localDeHoje();
        assertEquals(1L, depois.chamadas().get(Origem.BRIEFING));
        assertEquals(900, depois.entrada());
        assertEquals(2L, depois.reservas().get("pc_offline"));
        assertEquals(1L, depois.reservas().get("reprovado"));
        assertEquals(1, depois.errosDeFormato());
    }

    @Test
    @DisplayName("quem não usa a IA local tem o arquivo do dia como sempre foi")
    void semIaLocalNadaMuda() throws Exception {
        var medidor = medidor();
        medidor.registrar(Origem.CONVERSA, "modelo-a", 1_000, 10, 0, 0, 0);
        medidor.aguardar();
        try (var dias = Files.list(pasta)) {
            String bruto = Files.readString(dias.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow());
            assertFalse(bruto.contains("\"local\""));
            assertFalse(bruto.contains("reservas"));
            assertFalse(bruto.contains("errosDeFormato"));
        }
        assertTrue(medidor.localDeHoje().reservas().isEmpty());
    }
}
