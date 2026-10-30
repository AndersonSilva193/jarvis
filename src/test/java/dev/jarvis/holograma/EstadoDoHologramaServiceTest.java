package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A linha compacta do system e o "mais novo vence" entre o retrato da HUD e o que o servidor mandou. */
class EstadoDoHologramaServiceTest {

    private static final Instant AGORA = Instant.parse("2026-10-30T15:00:00Z");

    private static EstadoDoHolograma motor(Instant quando, double explosao) {
        return new EstadoDoHolograma(true, "h1a2b3c4d", "Motor V8", false,
                List.of(new EstadoDoHolograma.Peca("bloco", "Bloco", "bloco"),
                        new EstadoDoHolograma.Peca("p1", "Pistão esquerdo", "pistoes"),
                        new EstadoDoHolograma.Peca("p2", "Pistão direito", "pistoes")),
                explosao, "p1", List.of("pistoes"), List.of(), "frente", quando.toString());
    }

    @Test
    @DisplayName("a linha diz objeto, grupos com contagem, explosão, seleção, isolado e vista")
    void linha() {
        String linha = EstadoDoHologramaService.linha(motor(AGORA, 0.8));
        assertTrue(linha.startsWith("Holograma aberto: 'Motor V8' (3 peças, pronto). Grupos: bloco, pistoes (2). "
                + "Explosão 0.8. Selecionada: 'Pistão esquerdo' (pistoes). Isolado: pistoes. Vista: frente."), linha);
        assertTrue(linha.contains("ferramenta holograma"));
    }

    @Test
    @DisplayName("modo fechado, retrato velho ou nenhum retrato: nenhuma linha")
    void fechadoNaoEntra() {
        Clock relogio = Clock.fixed(AGORA, ZoneOffset.UTC);
        EstadoDoHologramaService servico = new EstadoDoHologramaService(relogio);
        assertEquals("", servico.linha());
        servico.guardar(EstadoDoHolograma.fechado(AGORA.toString()));
        assertEquals("", servico.linha());
        servico.guardar(motor(AGORA.minus(Duration.ofMinutes(11)), 0));
        assertEquals("", servico.linha());
        servico.guardar(motor(AGORA, 0));
        assertFalse(servico.linha().isEmpty());
    }

    @Test
    @DisplayName("grupos demais viram 'e mais N' e a linha tem teto")
    void teto() {
        List<EstadoDoHolograma.Peca> pecas = new ArrayList<>();
        for (int i = 0; i < 30; i++) pecas.add(new EstadoDoHolograma.Peca("p" + i, "Peça " + i, "grupo_comprido_" + i));
        String linha = EstadoDoHologramaService.linha(new EstadoDoHolograma(true, "", "Robô", true, pecas, 0, "",
                List.of(), List.of(), "", AGORA.toString()));
        assertTrue(linha.contains("construindo"));
        assertTrue(linha.split("\n")[0].length() <= EstadoDoHologramaService.MAX_LINHA, linha);
    }

    @Test
    @DisplayName("a explosão e a abertura mandadas pelo servidor valem até a HUD reportar algo mais novo")
    void maisNovoVence() {
        Instant[] agora = { AGORA };
        Clock relogio = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zona) { return this; }
            @Override public Instant instant() { return agora[0]; }
        };
        EstadoDoHologramaService servico = new EstadoDoHologramaService(relogio);
        assertFalse(servico.abertoAgora());
        servico.anotarEnvio(ComandoDoHolograma.de(ComandoDoHolograma.CARREGAR));
        assertTrue(servico.abertoAgora(), "carregar abre antes de a HUD contar");
        servico.anotarEnvio(ComandoDoHolograma.de(ComandoDoHolograma.EXPLODIR, Map.of("fator", 0.8)));
        assertEquals(0.8, servico.explosaoAtual());

        agora[0] = AGORA.plusSeconds(1);
        servico.guardar(motor(agora[0], 0.3));
        assertEquals(0.3, servico.explosaoAtual(), "o retrato mais novo vence");
    }
}
