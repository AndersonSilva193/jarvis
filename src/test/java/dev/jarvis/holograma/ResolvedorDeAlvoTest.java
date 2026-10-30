package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.holograma.ResolvedorDeAlvo.Empate;
import dev.jarvis.holograma.ResolvedorDeAlvo.Grupo;
import dev.jarvis.holograma.ResolvedorDeAlvo.Nenhum;
import dev.jarvis.holograma.ResolvedorDeAlvo.Pecas;

/** O alvo falado contra os nomes que o modelo deu às peças: acento, plural, aproximado e empate. */
class ResolvedorDeAlvoTest {

    private static EstadoDoHolograma.Peca peca(String id, String nome, String grupo) {
        return new EstadoDoHolograma.Peca(id, nome, grupo);
    }

    private static final List<EstadoDoHolograma.Peca> MOTOR = List.of(
            peca("bloco", "Bloco do motor", "bloco"),
            peca("pistao_e_1", "Pistão esquerdo", "pistoes"),
            peca("pistao_e_2", "Pistão esquerdo", "pistoes"),
            peca("pistao_d_1", "Pistão direito", "pistoes"),
            peca("pino_1", "Pino do pistão", "pinos_do_pistao"),
            peca("pino_biela", "Pino da biela", "bielas"),
            peca("vira", "Virabrequim", "virabrequim"),
            peca("eng_1", "Engrenagem", "engrenagens"),
            peca("anel_1", "Anel de vedação", "aneis"));

    @Test
    @DisplayName("plural e acento casam com o grupo: 'pistões', 'os pistoes', 'PISTÃO'")
    void pluralEAcento() {
        for (String alvo : List.of("pistões", "os pistoes", "PISTÃO", "o pistao")) {
            var r = ResolvedorDeAlvo.resolver(alvo, MOTOR);
            assertEquals(new Grupo("pistoes"), r, alvo);
        }
        assertEquals(new Grupo("engrenagens"), ResolvedorDeAlvo.resolver("engrenagem", MOTOR));
        assertEquals(new Grupo("aneis"), ResolvedorDeAlvo.resolver("anel", MOTOR));
    }

    @Test
    @DisplayName("o exato vence o contido: 'pistão' é o grupo pistoes, não os pinos do pistão")
    void exatoVence() {
        assertEquals(new Grupo("pistoes"), ResolvedorDeAlvo.resolver("pistão", MOTOR));
        assertEquals(new Grupo("pinos_do_pistao"), ResolvedorDeAlvo.resolver("pinos do pistão", MOTOR));
    }

    @Test
    @DisplayName("o nome de uma peça leva todas as cópias dela")
    void pecaComCopias() {
        var r = ResolvedorDeAlvo.resolver("pistão esquerdo", MOTOR);
        Pecas pecas = assertInstanceOf(Pecas.class, r);
        assertEquals(List.of("pistao_e_1", "pistao_e_2"), pecas.ids());
        assertEquals("pistoes", pecas.grupo());
    }

    @Test
    @DisplayName("uma letra trocada numa palavra comprida ainda acha: 'virabrequin'")
    void aproximado() {
        assertEquals(new Grupo("virabrequim"), ResolvedorDeAlvo.resolver("virabrequin", MOTOR));
    }

    @Test
    @DisplayName("dois candidatos no topo viram empate com as opções, nunca uma escolha")
    void empate() {
        Empate empate = assertInstanceOf(Empate.class, ResolvedorDeAlvo.resolver("pino", MOTOR));
        assertEquals(List.of("pinos do pistao", "Pino da biela"), empate.opcoes());
    }

    @Test
    @DisplayName("a peça com o nome do próprio grupo não é segunda opção")
    void semRedundancia() {
        var pecas = List.of(peca("bloco", "Bloco", "bloco"), peca("tampa", "Tampa do bloco", "tampas"));
        assertEquals(new Grupo("bloco"), ResolvedorDeAlvo.resolver("o bloco", pecas));
    }

    @Test
    @DisplayName("nada que case, alvo vazio ou objeto vazio: nenhum")
    void nenhum() {
        assertInstanceOf(Nenhum.class, ResolvedorDeAlvo.resolver("hélice", MOTOR));
        assertInstanceOf(Nenhum.class, ResolvedorDeAlvo.resolver("", MOTOR));
        assertInstanceOf(Nenhum.class, ResolvedorDeAlvo.resolver("essa peça", MOTOR));
        assertInstanceOf(Nenhum.class, ResolvedorDeAlvo.resolver("pistão", List.of()));
    }

    @Test
    @DisplayName("singular das regras do português")
    void singular() {
        assertEquals("pistao", ResolvedorDeAlvo.singular("pistoes"));
        assertEquals("pedal", ResolvedorDeAlvo.singular("pedais"));
        assertEquals("anel", ResolvedorDeAlvo.singular("aneis"));
        assertEquals("engrenagem", ResolvedorDeAlvo.singular("engrenagens"));
        assertEquals("motor", ResolvedorDeAlvo.singular("motores"));
        assertEquals("roda", ResolvedorDeAlvo.singular("rodas"));
        assertTrue(ResolvedorDeAlvo.perto("virabrequin", "virabrequim"));
        assertTrue(!ResolvedorDeAlvo.perto("eixo", "aixo"), "palavra curta não aceita letra trocada");
    }
}
