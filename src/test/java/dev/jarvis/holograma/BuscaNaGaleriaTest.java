package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.jarvis.holograma.BuscaNaGaleria.Achado;
import dev.jarvis.holograma.BuscaNaGaleria.Nada;
import dev.jarvis.holograma.BuscaNaGaleria.Varios;

/** "Abre o átomo de carbono da galeria" tem de achar o exemplo, e não gerar outro. */
class BuscaNaGaleriaTest {

    private static GaleriaDeHologramas.Item item(String id, String nome, String objeto, String detalhe, boolean fixo) {
        return new GaleriaDeHologramas.Item(id, nome, 40, "2026-10-30T12:00:00Z", fixo ? "exemplo" : "anthropic",
                detalhe, objeto, fixo, false);
    }

    private static final List<GaleriaDeHologramas.Item> GALERIA = List.of(
            item("exemplo_atomo", "Átomo de carbono", "", "normal", true),
            item("exemplo_motor_v8", "Motor V8", "", "normal", true),
            item("h00000001", "Turbina a jato", "turbina", "detalhado", false),
            item("h00000002", "Motor elétrico", "motor eletrico", "normal", false));

    private static BuscaNaGaleria.Resultado procurar(String pedido) {
        return BuscaNaGaleria.procurar(pedido, DetalheHolograma.NORMAL, GALERIA);
    }

    @Test
    @DisplayName("o exemplo fixo é achado pelo nome, com acento ou sem, e com 'da galeria' na frase")
    void exemplo() {
        for (String pedido : List.of("átomo de carbono", "o átomo de carbono da galeria", "atomo de carbono",
                "abre o átomo de carbono", "carbono")) {
            assertEquals(new Achado(GALERIA.get(0)), procurar(pedido), pedido);
        }
    }

    @Test
    @DisplayName("o pedido que gerou o item também acha, e outro nível de detalhe serve")
    void pedidoEOutroNivel() {
        assertEquals(new Achado(GALERIA.get(2)), procurar("turbina"));
        assertEquals(new Achado(GALERIA.get(2)), procurar("a turbina a jato"));
    }

    @Test
    @DisplayName("palavra a mais no pedido é objeto novo: 'motor V8 turbo' não abre o Motor V8")
    void palavraAMaisEhNovo() {
        assertInstanceOf(Nada.class, procurar("motor V8 turbo"));
        assertInstanceOf(Nada.class, procurar("átomo de oxigênio"));
        assertInstanceOf(Nada.class, procurar("da galeria"));
    }

    @Test
    @DisplayName("dois objetos diferentes casando viram pergunta; o exato desempata")
    void varios() {
        Varios varios = assertInstanceOf(Varios.class, procurar("motor"));
        assertEquals(List.of("Motor V8", "Motor elétrico"), varios.itens().stream().map(GaleriaDeHologramas.Item::nome).toList());
        assertEquals(new Achado(GALERIA.get(1)), procurar("motor v8"));
    }

    @Test
    @DisplayName("o mesmo objeto em dois níveis não é dúvida: vence o nível pedido")
    void mesmoObjetoDoisNiveis() {
        var rapido = item("h1", "Ponte", "ponte", "rapido", false);
        var detalhado = item("h2", "Ponte", "ponte", "detalhado", false);
        assertEquals(new Achado(detalhado),
                BuscaNaGaleria.procurar("ponte", DetalheHolograma.DETALHADO, List.of(rapido, detalhado)));
        assertEquals(new Achado(rapido),
                BuscaNaGaleria.procurar("ponte", DetalheHolograma.NORMAL, List.of(rapido, detalhado)));
    }
}
