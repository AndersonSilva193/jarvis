package dev.jarvis.voz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/**
 * O uso de voz por dia é o insumo da projeção do aviso antecipado — e, por isso, tem de sobreviver
 * ao restart e tem de recusar afirmar uma média que não tem.
 */
class DiarioDeVozTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 09h em São Paulo, para o dia local não ser o dia anterior. */
    private static final String MANHA = "2026-01-10T12:00:00Z";

    @TempDir
    Path pasta;

    private final RelogioFalso relogio = new RelogioFalso(MANHA);

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "",
                new JarvisProperties.Telegram("token", 1L), null, null, null, null, null,
                new JarvisProperties.Voz("elevenlabs", "chave", "voz-1", "eleven_flash_v2_5", 5),
                null, null, null);
    }

    private DiarioDeVoz diario() {
        return new DiarioDeVoz(props(), JSON, relogio, pasta);
    }

    private LocalDate hoje() {
        return LocalDate.ofInstant(relogio.instant(), props().zona());
    }

    @Test
    @DisplayName("o uso soma no dia de hoje e volta depois de um restart")
    void usoSobreviveAoRestart() {
        var diario = diario();
        diario.sintetizou(1_200);
        diario.sintetizou(800);
        assertEquals(2_000, diario.usoDoDia(hoje()));

        diario.gravarPendente();
        assertTrue(Files.exists(pasta.resolve("uso-diario.json")));

        // Outra instância, o mesmo arquivo: é o que acontece depois de um restart do container.
        assertEquals(2_000, diario().usoDoDia(hoje()));
    }

    @Test
    @DisplayName("com um dia completo só, não há média para projetar")
    void umDiaNaoDaMedia() {
        var diario = diario();
        diario.sintetizou(2_000);
        relogio.avancar(Duration.ofDays(1));

        assertTrue(diario.mediaDiaria().isEmpty(), "um dia completo não é ritmo");
    }

    @Test
    @DisplayName("hoje não entra na média: é um dia pela metade")
    void hojeNaoEntraNaMedia() {
        var diario = diario();
        diario.sintetizou(2_000);
        relogio.avancar(Duration.ofDays(1));
        diario.sintetizou(4_000);
        relogio.avancar(Duration.ofDays(1));
        // O terceiro dia é o de hoje, e um gasto enorme nele não pode mexer na média.
        diario.sintetizou(900_000);

        assertEquals(3_000, diario.mediaDiaria().getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("dia sem registro é dia omitido, não dia com zero")
    void diaSemRegistroNaoViraZero() {
        var diario = diario();
        diario.sintetizou(2_000);
        // Dois dias desligado no meio: se eles entrassem como zero, a média cairia para 1000.
        relogio.avancar(Duration.ofDays(3));
        diario.sintetizou(4_000);
        relogio.avancar(Duration.ofDays(1));

        assertEquals(3_000, diario.mediaDiaria().getAsDouble(), 0.001);
    }

    @Test
    @DisplayName("a média olha a janela recente, não o mês inteiro")
    void mediaOlhaAJanelaRecente() {
        var diario = diario();
        diario.sintetizou(100_000);
        relogio.avancar(Duration.ofDays(DiarioDeVoz.JANELA_DA_MEDIA + 1L));
        diario.sintetizou(1_000);
        relogio.avancar(Duration.ofDays(1));
        diario.sintetizou(3_000);
        relogio.avancar(Duration.ofDays(1));

        assertEquals(2_000, diario.mediaDiaria().getAsDouble(), 0.001,
                "o dia de 100 mil caiu fora da janela");
    }

    @Test
    @DisplayName("o marcador do período avisado volta depois de um restart")
    void marcadorDoPeriodoSobrevive() {
        var diario = diario();
        assertEquals("", diario.avisoDoPeriodo());

        diario.marcarAvisoDoPeriodo("2026-02-01T00:00:00Z");

        assertEquals("2026-02-01T00:00:00Z", diario().avisoDoPeriodo());
    }

    @Test
    @DisplayName("dia mais velho que o prazo sai do arquivo na virada")
    void expurgoNaViradaDoDia() {
        var diario = diario();
        LocalDate primeiro = hoje();
        diario.sintetizou(2_000);

        relogio.avancar(Duration.ofDays(DiarioDeVoz.DIAS_GUARDADOS + 1L));
        diario.sintetizou(500);
        diario.fecharODia();

        assertEquals(0, diario.usoDoDia(primeiro));
        assertEquals(500, diario.usoDoDia(hoje()));
        assertFalse(diario().usoDoDia(primeiro) > 0, "o expurgo também foi para o disco");
    }

    @Test
    @DisplayName("arquivo ilegível não impede o Jarvis de subir")
    void arquivoIlegivelNaoDerruba() throws Exception {
        Files.writeString(pasta.resolve("uso-diario.json"), "{ isso não é json");

        var diario = diario();

        assertEquals(0, diario.usoDoDia(hoje()));
        assertTrue(diario.mediaDiaria().isEmpty());
    }
}
