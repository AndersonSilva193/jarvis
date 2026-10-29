package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

class GaleriaDeHologramasTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path pasta;

    private GaleriaDeHologramas galeria() {
        return new GaleriaDeHologramas(JSON, pasta, new ExemplosDeHolograma(), new HologramaLineParser(JSON));
    }

    private static List<HologramaPeca> pecas(int n) {
        List<HologramaPeca> lista = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lista.add(new HologramaPeca("p_" + i, "Peça " + i, "geral", i == 0 ? "tube" : "box",
                    new double[] {i, 0, 0}, new double[] {0, 0, 0}, i == 0 ? new double[] {0.05} : new double[] {1, 1, 1},
                    "#38bdf8", new double[] {0, 1, 0}, 1, true,
                    i == 0 ? new double[][] {{0, 0, 0}, {1, 1, 1}} : null));
        }
        return lista;
    }

    private static final HologramaMeta META = new HologramaMeta("Motor V8", "mecanica", "um motor");

    @Test
    void aChaveIgnoraArtigoAcentoCaixaEEspaco() {
        assertEquals(GaleriaDeHologramas.chave("motor v8", DetalheHolograma.NORMAL),
                GaleriaDeHologramas.chave("  o Motor   V8 ", DetalheHolograma.NORMAL));
        assertEquals(GaleriaDeHologramas.chave("coração", DetalheHolograma.RAPIDO),
                GaleriaDeHologramas.chave("um CORACAO", DetalheHolograma.RAPIDO));
        assertNotEquals(GaleriaDeHologramas.chave("motor v8", DetalheHolograma.NORMAL),
                GaleriaDeHologramas.chave("motor v8", DetalheHolograma.DETALHADO));
        // Só artigo: não vira a chave vazia.
        assertEquals("a|normal", GaleriaDeHologramas.chave("A", DetalheHolograma.NORMAL));
    }

    @Test
    void guardaEReabreDepoisDoRestart() {
        assertTrue(galeria().salvar("habcdefgh", "o motor V8", DetalheHolograma.NORMAL, "anthropic", "claude-haiku-5-5",
                META, pecas(8)));

        GaleriaDeHologramas reaberta = galeria();
        GaleriaDeHologramas.HologramaSalvo salvo = reaberta.buscar("Motor v8", DetalheHolograma.NORMAL).orElseThrow();
        assertEquals("habcdefgh", salvo.id());
        assertEquals("Motor V8", salvo.meta().nome());
        assertEquals(8, salvo.pecas().size());
        assertArrayEquals(new double[] {1, 1, 1}, salvo.pecas().get(1).tamanho());
        assertArrayEquals(new double[] {1, 1, 1}, salvo.pecas().get(0).pontos()[1]);
        assertTrue(reaberta.buscar("motor v8", DetalheHolograma.DETALHADO).isEmpty());

        GaleriaDeHologramas.Item item = reaberta.listar().getLast();
        assertEquals("habcdefgh", item.id());
        assertEquals(8, item.pecas());
        assertEquals("anthropic", item.provedor());
        assertFalse(item.fixo());
    }

    @Test
    void menosDeOitoPecasNaoEntra() {
        GaleriaDeHologramas galeria = galeria();
        assertFalse(galeria.salvar("habcdefgh", "cubo", DetalheHolograma.NORMAL, "anthropic", "", META, pecas(7)));
        assertEquals(3, galeria.listar().size());
    }

    @Test
    void oMesmoPedidoSubstituiOAnterior() throws Exception {
        GaleriaDeHologramas galeria = galeria();
        galeria.salvar("haaaaaaaa", "motor v8", DetalheHolograma.NORMAL, "anthropic", "", META, pecas(8));
        Files.write(pasta.resolve("haaaaaaaa.png"), new byte[] {1});
        galeria.salvar("hbbbbbbbb", "O motor V8", DetalheHolograma.NORMAL, "local", "", META, pecas(9));

        assertEquals("hbbbbbbbb", galeria.buscar("motor v8", DetalheHolograma.NORMAL).orElseThrow().id());
        assertFalse(galeria.contem("haaaaaaaa"));
        assertFalse(Files.exists(pasta.resolve("haaaaaaaa.json")));
        assertFalse(Files.exists(pasta.resolve("haaaaaaaa.png")));
        assertEquals(4, galeria.listar().size());
    }

    @Test
    void osExemplosSaoFixosENaoRespondemAPedido() {
        GaleriaDeHologramas galeria = galeria();
        List<GaleriaDeHologramas.Item> itens = galeria.listar();
        assertEquals(List.of("exemplo_motor_v8", "exemplo_dna", "exemplo_atomo"),
                itens.stream().map(GaleriaDeHologramas.Item::id).toList());
        assertTrue(itens.stream().allMatch(GaleriaDeHologramas.Item::fixo));
        assertEquals(28, galeria.abrir("exemplo_atomo").orElseThrow().pecas().size());
        assertEquals(GaleriaDeHologramas.Remocao.FIXO, galeria.apagar("exemplo_atomo"));
        assertTrue(galeria.buscar(itens.getLast().nome(), DetalheHolograma.NORMAL).isEmpty());
    }

    @Test
    void idTortoNaoViraCaminho() {
        GaleriaDeHologramas galeria = galeria();
        assertTrue(galeria.abrir("../etc").isEmpty());
        assertEquals(GaleriaDeHologramas.Remocao.NAO_EXISTE, galeria.apagar("../x"));
        assertFalse(galeria.salvar("../fora", "x", DetalheHolograma.NORMAL, "anthropic", "", META, pecas(8)));
    }
}
