package dev.jarvis.tela;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Testa o retrato da tela: o formato que vai para o prompt e a validade do que a HUD mandou. */
class TelaEstadoServiceTest {

    private static EstadoDaTela.Janela janela(String id, String tipo, String titulo, String estado,
            boolean fixado, boolean emFoco, boolean tocando) {
        return new EstadoDaTela.Janela(id, tipo, titulo, estado, fixado, emFoco, tocando);
    }

    private static EstadoDaTela agora(List<EstadoDaTela.Janela> janelas) {
        return new EstadoDaTela(janelas, false, false, Instant.now().toString());
    }

    @Test
    @DisplayName("cada janela vira uma linha com id, tipo, título e situação")
    void formatoDaLinha() {
        EstadoDaTela estado = agora(List.of(
                janela("c7k2", CardTela.VIDEO, "Lo-fi para estudar", "normal", false, false, true),
                janela("cf09", CardTela.PESQUISA, "Eleição 2026", "normal", true, false, false)));

        assertEquals(List.of(
                "[c7k2] vídeo 'Lo-fi para estudar' (tocando)",
                "[cf09] pesquisa 'Eleição 2026' (fixada)"),
                TelaEstadoService.linhas(estado, TelaEstadoService.MAX_LINHAS));
    }

    @Test
    @DisplayName("o adjetivo concorda com o tipo: pesquisa fica fixada, vídeo fica fixado")
    void concordancia() {
        EstadoDaTela estado = agora(List.of(
                janela("c1", CardTela.VIDEO, "Clipe", "minimizado", true, false, false),
                janela("c2", CardTela.NOTICIA, "Manchete", "maximizado", false, true, false)));

        List<String> linhas = TelaEstadoService.linhas(estado, 12);
        assertEquals("[c1] vídeo 'Clipe' (minimizado, fixado)", linhas.get(0));
        assertEquals("[c2] notícia 'Manchete' (maximizada, em foco)", linhas.get(1));
    }

    @Test
    @DisplayName("janela em estado normal e sem nada marcado não ganha parênteses")
    void semAdjetivos() {
        EstadoDaTela estado = agora(List.of(
                janela("c3", CardTela.PAGINA, "Documentação", "normal", false, false, false)));

        assertEquals(List.of("[c3] página 'Documentação'"), TelaEstadoService.linhas(estado, 12));
    }

    @Test
    @DisplayName("histórico e briefing abertos rendem uma linha cada")
    void historicoEBriefing() {
        EstadoDaTela estado = new EstadoDaTela(
                List.of(janela("c1", CardTela.MUSICA, "Jazz", "normal", false, false, true)),
                true, true, Instant.now().toString());

        List<String> linhas = TelaEstadoService.linhas(estado, 12);
        assertEquals(3, linhas.size());
        assertTrue(linhas.get(1).contains("histórico"), linhas.get(1));
        assertTrue(linhas.get(2).contains("briefing"), linhas.get(2));
    }

    @Test
    @DisplayName("o teto de 12 linhas vale mesmo com a tela cheia")
    void tetoDeLinhas() {
        List<EstadoDaTela.Janela> muitas = new ArrayList<>();
        for (int i = 1; i <= 20; i++) {
            muitas.add(janela("c" + i, CardTela.PESQUISA, "Pesquisa " + i, "normal", false, false, false));
        }

        assertEquals(TelaEstadoService.MAX_LINHAS,
                TelaEstadoService.linhas(agora(muitas), TelaEstadoService.MAX_LINHAS).size(),
                "tela cheia não pode comer o contexto do pedido");
    }

    @Test
    @DisplayName("título comprido é cortado e a aspa de dentro não quebra a linha")
    void tituloComprido() {
        EstadoDaTela estado = agora(List.of(
                janela("c1", CardTela.PESQUISA, "x".repeat(200), "normal", false, false, false)));

        String linha = TelaEstadoService.linhas(estado, 12).getFirst();
        assertTrue(linha.length() < 100, linha);
        assertTrue(linha.endsWith("…'"), linha);

        EstadoDaTela comAspa = agora(List.of(
                janela("c2", CardTela.NOTICIA, "O 'melhor' resultado", "normal", false, false, false)));
        assertEquals("[c2] notícia 'O \"melhor\" resultado'",
                TelaEstadoService.linhas(comAspa, 12).getFirst());
    }

    @Test
    @DisplayName("o retrato guardado é devolvido enquanto está fresco")
    void guardaEDevolve() {
        TelaEstadoService tela = new TelaEstadoService();
        assertTrue(tela.ultimo().isEmpty(), "sem POST da HUD não há retrato");

        tela.guardar(agora(List.of(
                janela("c7k2", CardTela.VIDEO, "Lo-fi", "normal", false, false, true))));

        assertTrue(tela.ultimo().isPresent());
        assertTrue(tela.janela("c7k2").isPresent());
        assertTrue(tela.janela("c999").isEmpty());
        assertTrue(tela.janela("").isEmpty());
        assertEquals(1, tela.linhas().size());
    }

    @Test
    @DisplayName("retrato velho é tratado como inexistente")
    void retratoVelho() {
        TelaEstadoService tela = new TelaEstadoService();
        String onzeMinutosAtras = Instant.now().minus(Duration.ofMinutes(11)).toString();
        tela.guardar(new EstadoDaTela(
                List.of(janela("c1", CardTela.VIDEO, "Antigo", "normal", false, false, false)),
                false, false, onzeMinutosAtras));

        assertTrue(tela.ultimo().isEmpty(),
                "estado velho mandaria o modelo fechar uma janela que já não existe");
        assertTrue(tela.janela("c1").isEmpty());
        assertEquals(List.of(), tela.linhas());
    }

    @Test
    @DisplayName("retrato sem data, ou com data ilegível, não vale")
    void dataRuim() {
        TelaEstadoService tela = new TelaEstadoService();
        tela.guardar(new EstadoDaTela(List.of(), false, false, "ontem à tarde"));
        assertTrue(tela.ultimo().isEmpty());

        tela.guardar(new EstadoDaTela(List.of(), false, false, null));
        assertTrue(tela.ultimo().isEmpty());
    }

    @Test
    @DisplayName("a lista para o modelo diz o que está aberto, ou que não há nada")
    void listaParaOModelo() {
        TelaEstadoService tela = new TelaEstadoService();
        assertTrue(tela.listaParaOModelo().contains("Não há nada aberto"), tela.listaParaOModelo());

        tela.guardar(agora(List.of(
                janela("c7k2", CardTela.VIDEO, "Lo-fi", "normal", false, false, true))));

        String lista = tela.listaParaOModelo();
        assertTrue(lista.contains("c7k2"), lista);
        assertFalse(lista.contains("Não há nada aberto"), lista);
    }
}
