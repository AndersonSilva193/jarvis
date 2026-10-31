package dev.jarvis.holograma;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Conserto, descarte, expansão de rep/rad e os limites do objeto inteiro. */
class HologramaValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final double MARGEM = 1e-3;

    private final HologramaValidator validador = new HologramaValidator();

    private HologramaValidator.Validacao validar(String compacta) {
        JsonNode no = JSON.readTree(compacta);
        return validador.validar(no);
    }

    private HologramaPeca uma(String compacta) {
        var validacao = validar(compacta);
        assertEquals(1, validacao.pecas().size(), validacao.avisos().toString());
        return validacao.pecas().getFirst();
    }

    @Test
    @DisplayName("peça bem escrita passa como veio, sem aviso")
    void pecaBoa() {
        var validacao = validar("{\"t\":\"p\",\"id\":\"bloco\",\"n\":\"Bloco do motor\",\"g\":\"bloco\",\"f\":\"box\","
                + "\"p\":[0,0,0],\"r\":[0,0,0],\"s\":[3,1.6,1.4],\"c\":\"#38bdf8\",\"e\":[0,-1,0],\"o\":0.25}");
        assertEquals(List.of(), validacao.avisos());
        HologramaPeca peca = validacao.pecas().getFirst();
        assertEquals("bloco", peca.id());
        assertEquals("Bloco do motor", peca.nome());
        assertEquals("box", peca.forma());
        assertArrayEquals(new double[] { 3, 1.6, 1.4 }, peca.tamanho());
        assertArrayEquals(new double[] { 0, -1, 0 }, peca.explosao());
        assertEquals(0.25, peca.opacidade());
        assertTrue(peca.contorno());
        assertEquals(0, peca.pontos().length);
    }

    @Test
    @DisplayName("forma desconhecida vira box, com aviso")
    void formaDesconhecida() {
        var validacao = validar("{\"id\":\"x\",\"f\":\"piramide\",\"s\":[1,2,3]}");
        assertEquals("box", validacao.pecas().getFirst().forma());
        assertArrayEquals(new double[] { 1, 2, 3 }, validacao.pecas().getFirst().tamanho());
        assertTrue(validacao.avisos().getFirst().contains("piramide"), validacao.avisos().toString());
    }

    @Test
    @DisplayName("tamanho faltando ou torto vira o padrão da forma, posição por posição")
    void tamanhoPadrao() {
        assertArrayEquals(new double[] { 0.2, 0.5, 1 }, uma("{\"id\":\"a\",\"f\":\"cyl\",\"s\":[0.2]}").tamanho());
        assertArrayEquals(new double[] { 0.5 }, uma("{\"id\":\"b\",\"f\":\"sph\"}").tamanho());
        assertArrayEquals(new double[] { 0.5, 2, 3, 0.05 },
                uma("{\"id\":\"c\",\"f\":\"helix\",\"s\":\"grande\"}").tamanho());
        assertArrayEquals(new double[] { 1, 1, 0.4 },
                uma("{\"id\":\"d\",\"f\":\"box\",\"s\":[\"x\",null,0.4,9,9]}").tamanho());
    }

    @Test
    @DisplayName("posição fica em ±6 e tamanho entre 0.01 e 8; número que estoura o double vira zero")
    void limites() {
        HologramaPeca peca = uma("{\"id\":\"a\",\"f\":\"box\",\"p\":[99,-7,1e400],\"s\":[0,20,-3]}");
        assertArrayEquals(new double[] { 6, -6, 0 }, peca.posicao());
        assertArrayEquals(new double[] { 0.01, 8, 0.01 }, peca.tamanho());
    }

    @Test
    @DisplayName("rotação vai para (-180, 180]")
    void rotacao() {
        assertArrayEquals(new double[] { 180, -90, 10 },
                uma("{\"id\":\"a\",\"f\":\"box\",\"r\":[540,270,-350]}").rotacao());
    }

    @Test
    @DisplayName("cor inválida vira o ciano; cor curta é expandida e tudo sai em minúsculas")
    void cor() {
        assertEquals(HologramaValidator.COR_PADRAO, uma("{\"id\":\"a\",\"c\":\"azul\"}").cor());
        assertEquals(HologramaValidator.COR_PADRAO, uma("{\"id\":\"b\",\"c\":\"#12345g\"}").cor());
        assertEquals(HologramaValidator.COR_PADRAO, uma("{\"id\":\"c\"}").cor());
        assertEquals("#ffaa00", uma("{\"id\":\"d\",\"c\":\"#FA0\"}").cor());
        assertEquals("#f472b6", uma("{\"id\":\"e\",\"c\":\" #F472B6 \"}").cor());
    }

    @Test
    @DisplayName("opacidade presa a 0.05..1; contorno só desliga com false explícito")
    void opacidadeEContorno() {
        assertEquals(1, uma("{\"id\":\"a\"}").opacidade());
        assertEquals(0.05, uma("{\"id\":\"b\",\"o\":0}").opacidade());
        assertEquals(1, uma("{\"id\":\"c\",\"o\":3}").opacidade());
        assertFalse(uma("{\"id\":\"d\",\"ct\":false}").contorno());
        assertTrue(uma("{\"id\":\"e\",\"ct\":\"nao\"}").contorno());
    }

    @Test
    @DisplayName("ids repetidos ganham sufixo; id vira snake_case sem acento; sem id vira 'peca'")
    void idsUnicos() {
        assertEquals("pistao", uma("{\"id\":\"pistao\"}").id());
        var repetido = validar("{\"id\":\"pistao\"}");
        assertEquals("pistao_2", repetido.pecas().getFirst().id());
        assertTrue(repetido.avisos().getFirst().contains("pistao_2"));
        assertEquals("pistao_3", uma("{\"id\":\"pistao\"}").id());
        assertEquals("pistao_esquerdo", uma("{\"id\":\"  Pistão Esquerdo! \"}").id());
        assertEquals("peca", uma("{\"n\":\"Sem id\"}").id());
        assertEquals(HologramaValidator.MAX_ID, uma("{\"id\":\"" + "a".repeat(80) + "\"}").id().length());
    }

    @Test
    @DisplayName("nome e grupo têm padrão: o nome cai no id e o grupo em 'geral'")
    void padroesDeTexto() {
        HologramaPeca peca = uma("{\"id\":\"eixo\",\"g\":\"Virabrequim Principal\"}");
        assertEquals("eixo", peca.nome());
        assertEquals("virabrequim_principal", peca.grupo());
        assertEquals(HologramaValidator.GRUPO_PADRAO, uma("{\"id\":\"x\"}").grupo());
    }

    @Test
    @DisplayName("rep: n cópias contando a original, deslocadas i·d, ids id_1..id_n")
    void repLinear() {
        var validacao = validar("{\"id\":\"pistao_e\",\"f\":\"cyl\",\"p\":[-1.05,0.9,-0.45],\"r\":[0,0,-45],"
                + "\"e\":[-1,1,0],\"rep\":{\"n\":4,\"d\":[0.7,0,0]}}");
        List<HologramaPeca> pecas = validacao.pecas();
        assertEquals(4, pecas.size());
        assertEquals(List.of("pistao_e_1", "pistao_e_2", "pistao_e_3", "pistao_e_4"),
                pecas.stream().map(HologramaPeca::id).toList());
        assertArrayEquals(new double[] { -1.05, 0.9, -0.45 }, pecas.get(0).posicao(), MARGEM);
        assertArrayEquals(new double[] { 1.05, 0.9, -0.45 }, pecas.get(3).posicao(), MARGEM);
        // No rep a rotação e a direção de explosão não mudam.
        assertArrayEquals(new double[] { 0, 0, -45 }, pecas.get(3).rotacao());
        assertArrayEquals(pecas.get(0).explosao(), pecas.get(3).explosao());
        assertEquals(4, validador.total());
    }

    @Test
    @DisplayName("as cópias de um rep/rad levam a série (o id da primeira); a peça avulsa, nada no JSON")
    void serieDasCopias() {
        validar("{\"id\":\"dente\",\"f\":\"box\",\"rad\":{\"n\":3,\"r\":2}}");
        // O mesmo id cru de novo: as cópias viram dente_1_2... e a série é a delas, não a de cima.
        List<HologramaPeca> segunda = validar("{\"id\":\"dente\",\"f\":\"box\",\"rep\":{\"n\":2,\"d\":[1,0,0]}}").pecas();
        assertEquals(List.of(segunda.getFirst().id(), segunda.getFirst().id()),
                segunda.stream().map(HologramaPeca::serie).toList());
        assertFalse(segunda.getFirst().id().equals("dente_1"));
        assertEquals(List.of("dente_1", "dente_1", "dente_1"), new HologramaValidator()
                .validar(JSON.readTree("{\"id\":\"dente\",\"f\":\"box\",\"rad\":{\"n\":3,\"r\":2}}")).pecas().stream()
                .map(HologramaPeca::serie).toList());

        HologramaPeca avulsa = uma("{\"id\":\"bloco\",\"f\":\"box\"}");
        assertEquals(null, avulsa.serie());
        assertFalse(JSON.writeValueAsString(avulsa).contains("serie"));
        assertTrue(JSON.writeValueAsString(segunda.getFirst()).contains("\"serie\":\"" + segunda.getFirst().id() + "\""));
    }

    @Test
    @DisplayName("rep com n 1 ou ausente não expande nem põe sufixo")
    void repDeUm() {
        assertEquals("solo", uma("{\"id\":\"solo\",\"rep\":{\"n\":1,\"d\":[1,0,0]}}").id());
        assertEquals("sem_n", uma("{\"id\":\"sem_n\",\"rep\":{\"d\":[1,0,0]}}").id());
    }

    @Test
    @DisplayName("rad em y: círculo em volta de p, cada cópia girando e se afastando para fora")
    void radEmY() {
        List<HologramaPeca> pecas = validar("{\"id\":\"dente\",\"f\":\"box\",\"p\":[0,1,0],\"e\":[1,0,0],"
                + "\"rad\":{\"n\":4,\"r\":2,\"ax\":\"y\"}}").pecas();
        assertEquals(4, pecas.size());
        assertArrayEquals(new double[] { 2, 1, 0 }, pecas.get(0).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 1, -2 }, pecas.get(1).posicao(), MARGEM);
        assertArrayEquals(new double[] { -2, 1, 0 }, pecas.get(2).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 1, 2 }, pecas.get(3).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 90, 0 }, pecas.get(1).rotacao(), MARGEM);
        assertArrayEquals(new double[] { 0, -90, 0 }, pecas.get(3).rotacao(), MARGEM);
        assertArrayEquals(new double[] { 0, 0, -1 }, pecas.get(1).explosao(), MARGEM);
        assertArrayEquals(new double[] { -1, 0, 0 }, pecas.get(2).explosao(), MARGEM);
    }

    @Test
    @DisplayName("rad em x começa no +y; em z começa no +x; 'a' é o ângulo inicial")
    void radEmXeZ() {
        List<HologramaPeca> emX = validar("{\"id\":\"x\",\"rad\":{\"n\":4,\"r\":1,\"ax\":\"x\"}}").pecas();
        assertArrayEquals(new double[] { 0, 1, 0 }, emX.get(0).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 0, 1 }, emX.get(1).posicao(), MARGEM);
        assertArrayEquals(new double[] { 90, 0, 0 }, emX.get(1).rotacao(), MARGEM);

        List<HologramaPeca> emZ = validar("{\"id\":\"z\",\"rad\":{\"n\":4,\"r\":1,\"ax\":\"z\",\"a\":90}}").pecas();
        assertArrayEquals(new double[] { 0, 1, 0 }, emZ.get(0).posicao(), MARGEM);
        assertArrayEquals(new double[] { -1, 0, 0 }, emZ.get(1).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 0, 180 }, emZ.get(1).rotacao(), MARGEM);
    }

    @Test
    @DisplayName("rad sobre peça já girada compõe as rotações em vez de somar ângulo")
    void radComRotacaoPrevia() {
        List<HologramaPeca> pecas = validar("{\"id\":\"orbita\",\"f\":\"tor\",\"r\":[30,0,0],"
                + "\"rad\":{\"n\":3,\"r\":0,\"ax\":\"y\"}}").pecas();
        double[][] esperada = multiplicar(Rotacoes.matriz(new double[] { 0, 120, 0 }),
                Rotacoes.matriz(new double[] { 30, 0, 0 }));
        double[][] obtida = Rotacoes.matriz(pecas.get(1).rotacao());
        for (int i = 0; i < 3; i++) {
            assertArrayEquals(esperada[i], obtida[i], MARGEM);
        }
    }

    @Test
    @DisplayName("rep e rad juntos fazem hélice: rep conta as cópias, rad diz quantas por volta")
    void helice() {
        List<HologramaPeca> pecas = validar("{\"id\":\"par\",\"f\":\"cyl\",\"p\":[0,-2,0],\"r\":[0,0,90],"
                + "\"rep\":{\"n\":13,\"d\":[0,0.44,0]},\"rad\":{\"n\":5,\"r\":0,\"ax\":\"y\",\"a\":36}}").pecas();
        assertEquals(13, pecas.size());
        assertArrayEquals(new double[] { 0, -2, 0 }, pecas.get(0).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 36, 90 }, pecas.get(0).rotacao(), MARGEM);
        assertArrayEquals(new double[] { 0, -1.12, 0 }, pecas.get(2).posicao(), MARGEM);
        assertArrayEquals(new double[] { 0, 180, 90 }, pecas.get(2).rotacao(), MARGEM);
    }

    @Test
    @DisplayName("tube e line sem dois pontos válidos são descartados, com aviso")
    void tuboSemPontos() {
        var semPts = validar("{\"id\":\"cano\",\"f\":\"tube\"}");
        assertTrue(semPts.pecas().isEmpty());
        assertTrue(semPts.avisos().getFirst().contains("cano"));
        var umPonto = validar("{\"id\":\"fio\",\"f\":\"line\",\"pts\":[[0,0,0],[1,\"x\",0],[1,1]]}");
        assertTrue(umPonto.pecas().isEmpty());
        assertEquals(0, validador.total());
    }

    @Test
    @DisplayName("pontos ficam no cubo, viram cópia por peça e são cortados em 32")
    void pontos() {
        HologramaPeca linha = uma("{\"id\":\"fio\",\"f\":\"line\",\"s\":[3],\"pts\":[[0,0,0],[10,0,-10]]}");
        assertArrayEquals(new double[] { 6, 0, -6 }, linha.pontos()[1]);
        assertEquals(0, linha.tamanho().length);

        StringBuilder muitos = new StringBuilder("[");
        for (int i = 0; i < 40; i++) {
            muitos.append(i == 0 ? "" : ",").append("[0,").append(i * 0.1).append(",0]");
        }
        var validacao = validar("{\"id\":\"mola\",\"f\":\"tube\",\"pts\":" + muitos + "]}");
        assertEquals(HologramaValidator.MAX_PONTOS, validacao.pecas().getFirst().pontos().length);
        assertEquals(1, validacao.avisos().size());

        List<HologramaPeca> copias = validar("{\"id\":\"cabo\",\"f\":\"tube\",\"pts\":[[0,0,0],[1,0,0]],"
                + "\"rep\":{\"n\":2,\"d\":[0,1,0]}}").pecas();
        assertFalse(copias.get(0).pontos() == copias.get(1).pontos());
        assertArrayEquals(copias.get(0).pontos()[1], copias.get(1).pontos()[1]);
    }

    @Test
    @DisplayName("forma que não é tube nem line ignora pts")
    void ptsForaDeLugar() {
        assertEquals(0, uma("{\"id\":\"a\",\"f\":\"box\",\"pts\":[[0,0,0],[1,1,1]]}").pontos().length);
    }

    @Test
    @DisplayName("anel escrito ao contrário é desvirado")
    void anelInvertido() {
        assertArrayEquals(new double[] { 0.5, 1.2 }, uma("{\"id\":\"a\",\"f\":\"ring\",\"s\":[1.2,0.5]}").tamanho());
        double[] igual = uma("{\"id\":\"b\",\"f\":\"ring\",\"s\":[1,1]}").tamanho();
        assertTrue(igual[0] < igual[1]);
    }

    @Test
    @DisplayName("sem direção de explosão, a peça se afasta pela própria posição; no centro, sobe")
    void explosaoPadrao() {
        assertArrayEquals(new double[] { 0.6, 0.8, 0 }, uma("{\"id\":\"a\",\"p\":[3,4,0]}").explosao(), MARGEM);
        assertArrayEquals(new double[] { 0, 1, 0 }, uma("{\"id\":\"b\"}").explosao());
        assertArrayEquals(new double[] { 0, 0, -1 }, uma("{\"id\":\"c\",\"e\":[0,0,-5]}").explosao());
    }

    @Test
    @DisplayName("limite de 250: a linha que estoura entra pela metade e o resto é descartado, avisando uma vez")
    void limiteDoObjeto() {
        for (int i = 0; i < 4; i++) {
            assertEquals(60, validar("{\"id\":\"lote" + i + "\",\"rep\":{\"n\":60,\"d\":[0.01,0,0]}}").pecas().size());
        }
        var estouro = validar("{\"id\":\"lote4\",\"rep\":{\"n\":60,\"d\":[0.01,0,0]}}");
        assertEquals(10, estouro.pecas().size());
        assertEquals(2, estouro.avisos().size(), estouro.avisos().toString());
        assertEquals(HologramaValidator.MAX_PECAS, validador.total());

        var depois = validar("{\"id\":\"mais_uma\"}");
        assertTrue(depois.pecas().isEmpty());
        assertTrue(depois.avisos().isEmpty());
        assertEquals(51, validador.descartadasPeloLimite());
    }

    @Test
    @DisplayName("rep acima do teto de cópias é preso a 60")
    void tetoDeCopias() {
        assertEquals(HologramaValidator.MAX_COPIAS, validar("{\"id\":\"a\",\"rep\":{\"n\":1000,\"d\":[0,0,0]}}").pecas().size());
    }

    @Test
    @DisplayName("o que não é objeto é descartado com aviso")
    void naoObjeto() {
        var validacao = validador.validar(JSON.readTree("[1,2]"));
        assertTrue(validacao.pecas().isEmpty());
        assertEquals(1, validacao.avisos().size());
    }

    private static double[][] multiplicar(double[][] a, double[][] b) {
        double[][] r = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return r;
    }
}
