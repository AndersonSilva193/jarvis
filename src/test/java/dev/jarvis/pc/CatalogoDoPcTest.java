package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.pc.OperacoesPcService.RotinaPublica;
import tools.jackson.databind.json.JsonMapper;

/**
 * O catálogo que o PC anuncia, e por que ele mora em disco.
 *
 * <p>Apps e rotinas entram na {@code descricao()} e no {@code esquemaEntrada()} de
 * {@code controlar_pc} e {@code rodar_rotina} — ou seja, no array {@code tools}, que é renderizado
 * antes do {@code system}. Esvaziar as listas num restart reconstruía o prefixo inteiro do cache de
 * prompt, duas vezes: no primeiro pedido depois da subida e de novo quando o agente reaparecesse.
 */
class CatalogoDoPcTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static CatalogoDoPc em(Path pasta) {
        return new CatalogoDoPc(JSON, pasta.resolve("catalogo.json"));
    }

    @Test
    @DisplayName("os apps anunciados voltam depois de um restart")
    void appsSobrevivemAoRestart(@TempDir Path pasta) {
        em(pasta).anunciarApps(List.of("chrome", "vscode"));

        assertEquals(List.of("chrome", "vscode"), em(pasta).apps(),
                "um restart esvaziando a lista tira abrir_app do enum e reconstrói o prefixo");
    }

    @Test
    @DisplayName("as rotinas anunciadas voltam depois de um restart, com descrição e capacidades")
    void rotinasSobrevivemAoRestart(@TempDir Path pasta) {
        var rotina = new RotinaPublica("publicar", "sobe o site", List.of("publica", "rede"));
        em(pasta).anunciarRotinas(List.of(rotina));

        assertEquals(List.of(rotina), em(pasta).rotinas());
    }

    @Test
    @DisplayName("anúncio ausente não muda nada; anúncio vazio muda")
    void ausenteNaoMudaVazioMuda(@TempDir Path pasta) {
        CatalogoDoPc catalogo = em(pasta);
        catalogo.anunciarApps(List.of("chrome"));

        // Cabeçalho ausente é falta de informação: um agente antigo não apaga o enum no meio da
        // sessão. É a assimetria que o PcService.registrarApps documenta, e ela continua valendo.
        catalogo.anunciarApps(null);
        assertEquals(List.of("chrome"), catalogo.apps());

        // Cabeçalho presente e vazio é informação explícita de que não há app liberado.
        catalogo.anunciarApps(List.of());
        assertEquals(List.of(), catalogo.apps());
    }

    @Test
    @DisplayName("anunciar a mesma lista de novo não grava de novo")
    void anuncioIgualNaoGrava(@TempDir Path pasta) throws IOException {
        Path arquivo = pasta.resolve("catalogo.json");
        CatalogoDoPc catalogo = em(pasta);
        catalogo.anunciarApps(List.of("chrome"));
        assertTrue(Files.isRegularFile(arquivo));

        // Apagar e reanunciar o mesmo: se houvesse gravação, o arquivo voltaria. O agente faz long
        // poll a cada 25 s, e gravar a cada batida seria escrita em volume Docker para nada.
        Files.delete(arquivo);
        catalogo.anunciarApps(List.of("chrome"));

        assertFalse(Files.isRegularFile(arquivo), "lista igual não é anúncio de lista diferente");
    }

    @Test
    @DisplayName("o catálogo em memória não toca o disco")
    void emMemoriaNaoGrava(@TempDir Path pasta) {
        CatalogoDoPc catalogo = CatalogoDoPc.emMemoria();
        catalogo.anunciarApps(List.of("chrome"));

        assertEquals(List.of("chrome"), catalogo.apps());
        assertFalse(Files.exists(pasta.resolve("catalogo.json")));
    }

    @Test
    @DisplayName("arquivo corrompido não derruba nada: volta lista vazia e segue")
    void arquivoCorrompidoNaoDerruba(@TempDir Path pasta) throws IOException {
        Files.writeString(pasta.resolve("catalogo.json"), "{isso nao e json");

        CatalogoDoPc catalogo = em(pasta);

        assertEquals(List.of(), catalogo.apps());
        assertEquals(List.of(), catalogo.rotinas());
    }

    @Test
    @DisplayName("o PcService lê os apps do catálogo, e o restart não os perde")
    void pcServiceLeDoCatalogo(@TempDir Path pasta) {
        em(pasta).anunciarApps(List.of("chrome"));

        var e2 = new OperacoesPcService(JSON, pasta.resolve("operacoes.json"),
                java.time.Clock.systemUTC(), em(pasta));
        var pc = new PcService(e2, em(pasta));

        assertEquals(List.of("chrome"), pc.appsConhecidos());
    }

    @Test
    @DisplayName("as rotinas validadas pelo OperacoesPcService vão para o catálogo e persistem")
    void rotinasValidadasPersistem(@TempDir Path pasta) {
        CatalogoDoPc catalogo = em(pasta);
        var e2 = new OperacoesPcService(JSON, pasta.resolve("operacoes.json"),
                java.time.Clock.systemUTC(), catalogo);

        e2.catalogo(List.of(new RotinaPublica("publicar", "sobe o site", List.of("publica")),
                new RotinaPublica("NOME INVÁLIDO", "x", List.of("leitura"))));

        assertEquals(List.of("publicar"), e2.rotinas().stream().map(RotinaPublica::nome).toList(),
                "a higienização continua sendo do OperacoesPcService; o catálogo só guarda");
        assertEquals(List.of("publicar"),
                em(pasta).rotinas().stream().map(RotinaPublica::nome).toList());
    }
}
