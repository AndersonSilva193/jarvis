package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.diario.*;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.tela.DadosDoCard;
import tools.jackson.databind.json.JsonMapper;

class AcoesLinhaDoTempoTest {
    @TempDir Path pasta;
    @Test void cardTemDiaInteiroMasModeloRecebeResumoFiltradoEIds() throws Exception {
        var props = mock(JarvisProperties.class); when(props.zona()).thenReturn(ZoneId.of("America/Sao_Paulo"));
        var json = JsonMapper.builder().build(); var relogio = new RelogioFalso("2026-11-03T17:00:00Z");
        var diario = new DiarioDeAcoes(props, json, relogio, pasta);
        try {
            for (int i = 0; i < 12; i++) diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei nota-" + i + ".md", OrigemDaAcao.HUD, 42, new Desfazer.Arquivo("arquivo-" + i));
            diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "segredo outro usuário", OrigemDaAcao.HUD, 99, new Desfazer.Arquivo("outro"));
            var ferramenta = new AcoesDoJarvisFerramenta(diario, json); ferramenta.configurarPresenca(() -> 1);
            OuvinteProgresso hud = contexto("hud");
            var resultado = ferramenta.executarDetalhado(json.createObjectNode(), hud);
            assertEquals(1, resultado.cards().size());
            var dados = (DadosDoCard.Pesquisa) resultado.cards().getFirst().dados();
            assertEquals("linha_do_tempo", dados.layout()); assertEquals(12, dados.itens().size());
            assertTrue(dados.fontes().isEmpty());
            assertTrue(resultado.texto().contains("E mais 4"));
            assertFalse(resultado.texto().contains("segredo"));
            assertFalse(json.writeValueAsString(resultado.cards()).contains("segredo"));
            var filtrado = ferramenta.executarDetalhado(json.createObjectNode().put("horario", "14").put("texto", "nota-0.md"), hud);
            assertTrue(filtrado.texto().contains("1 ação.")); assertTrue(filtrado.texto().contains("[a"));
            assertEquals(12, ((DadosDoCard.Pesquisa) filtrado.cards().getFirst().dados()).itens().size());
            assertTrue(ferramenta.executarDetalhado(json.createObjectNode(), contexto("telegram")).cards().isEmpty());
            ferramenta.configurarPresenca(() -> 0);
            assertTrue(ferramenta.executarDetalhado(json.createObjectNode(), hud).cards().isEmpty());
        } finally { diario.fechar(); }
    }
    private static OuvinteProgresso contexto(String canal) { return new OuvinteProgresso() {
        @Override public long usuarioAutenticado() { return 42; }
        @Override public String canal() { return canal; }
    }; }
}
