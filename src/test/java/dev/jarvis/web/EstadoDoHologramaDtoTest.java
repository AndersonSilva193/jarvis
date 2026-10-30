package dev.jarvis.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.holograma.EstadoDoHolograma;

/** O retrato do holograma vai ao prompt e à resolução de alvo: campo torto reprova o retrato inteiro. */
class EstadoDoHologramaDtoTest {

    private static EstadoDoHologramaDto aberto(List<EstadoDoHologramaDto.PecaDto> pecas, Double explosao, String vista) {
        return new EstadoDoHologramaDto(true, new EstadoDoHologramaDto.Objeto("h1a2b3c4d", "Motor V8"), false,
                pecas, explosao, "p1", List.of("pistoes"), List.of(), vista);
    }

    @Test
    @DisplayName("um retrato bom vira domínio com as peças e o nome")
    void bom() {
        var dto = aberto(List.of(new EstadoDoHologramaDto.PecaDto("p1", "Pistão", "pistoes")), 0.8, "frente");
        assertTrue(dto.erros().isEmpty(), dto.erros().toString());
        EstadoDoHolograma estado = dto.paraDominio("2026-10-30T15:00:00Z");
        assertTrue(estado.aberto());
        assertEquals("Motor V8", estado.nome());
        assertEquals("pistoes", estado.pecas().get(0).grupo());
    }

    @Test
    @DisplayName("id ou grupo fora do snake_case, explosão fora de 0..1 e vista desconhecida reprovam")
    void tortos() {
        assertFalse(aberto(List.of(new EstadoDoHologramaDto.PecaDto("P 1", "x", "g")), 0.0, "").erros().isEmpty());
        assertFalse(aberto(List.of(new EstadoDoHologramaDto.PecaDto("p1", "x", "<g>")), 0.0, "").erros().isEmpty());
        assertFalse(aberto(List.of(), 1.5, "").erros().isEmpty());
        assertFalse(aberto(List.of(), 0.0, "de baixo").erros().isEmpty());
        assertFalse(new EstadoDoHologramaDto(null, null, null, null, null, null, null, null, null).erros().isEmpty());
    }

    @Test
    @DisplayName("fechado vira o estado vazio, sem o que a HUD mandou junto")
    void fechado() {
        var dto = new EstadoDoHologramaDto(false, null, null, null, null, null, null, null, null);
        assertTrue(dto.erros().isEmpty());
        EstadoDoHolograma estado = dto.paraDominio("2026-10-30T15:00:00Z");
        assertFalse(estado.aberto());
        assertTrue(estado.pecas().isEmpty());
    }
}
