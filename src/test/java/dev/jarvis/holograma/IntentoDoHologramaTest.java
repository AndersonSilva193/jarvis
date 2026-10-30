package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** O caminho rápido: a frase curta inteira casa, e qualquer palavra a mais vai para o modelo. */
class IntentoDoHologramaTest {

    record Esperado(String acao, String valor) { }

    private static Optional<Esperado> de(String texto) {
        return IntentoDoHolograma.reconhecer(texto).map(i -> new Esperado(i.acao(), i.valor()));
    }

    @Test
    @DisplayName("os comandos curtos e frequentes, com e sem enfeite")
    void curtos() {
        Map<String, Esperado> casos = Map.ofEntries(
                Map.entry("explode", new Esperado("explodir", "1")),
                Map.entry("Jarvis, explode!", new Esperado("explodir", "1")),
                Map.entry("explode de novo", new Esperado("explodir", "1")),
                Map.entry("monta", new Esperado("montar", "")),
                Map.entry("monta por favor", new Esperado("montar", "")),
                Map.entry("gira", new Esperado("girar_continuo", "")),
                Map.entry("para", new Esperado("parar", "")),
                Map.entry("para de girar", new Esperado("parar", "")),
                Map.entry("mais", new Esperado("explodir", "mais")),
                Map.entry("mais um pouco", new Esperado("explodir", "mais")),
                Map.entry("menos", new Esperado("explodir", "menos")),
                Map.entry("explode mais", new Esperado("explodir", "mais")),
                Map.entry("reseta", new Esperado("resetar", "")),
                Map.entry("reseta a vista", new Esperado("resetar", "")),
                Map.entry("vista de cima", new Esperado("vista", "cima")),
                Map.entry("Vista de frente.", new Esperado("vista", "frente")),
                Map.entry("de lado", new Esperado("vista", "lado")),
                Map.entry("vista isométrica", new Esperado("vista", "isometrica")),
                Map.entry("aproxima", new Esperado("zoom", "aproximar")),
                Map.entry("fecha", new Esperado("fechar", "")),
                Map.entry("fecha o holograma", new Esperado("fechar", "")));
        casos.forEach((texto, esperado) -> assertEquals(Optional.of(esperado), de(texto), texto));
    }

    @Test
    @DisplayName("frase com objeto ou pergunta é do modelo")
    void longasNaoCasam() {
        for (String texto : new String[] { "explode só os pistões", "o que é essa peça?",
                "carrega o motor V8", "mais detalhes sobre o virabrequim", "para o vídeo",
                "fecha a janela do vídeo", "abre o holograma e carrega o motor V8 explodido", "", "jarvis" }) {
            assertTrue(de(texto).isEmpty(), texto);
        }
    }
}
