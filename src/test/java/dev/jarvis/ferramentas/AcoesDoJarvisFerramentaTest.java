package dev.jarvis.ferramentas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.OrigemDaAcao;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** O que a ferramenta fala: os três períodos, o filtro de assunto e o dia vazio. */
class AcoesDoJarvisFerramentaTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @TempDir
    Path pasta;

    private final List<DiarioDeAcoes> criados = new ArrayList<>();

    @AfterEach
    void fechar() {
        criados.forEach(DiarioDeAcoes::fechar);
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
    }

    private DiarioDeAcoes diario(RelogioFalso relogio) {
        var novo = new DiarioDeAcoes(props(), JSON, relogio, pasta);
        criados.add(novo);
        return novo;
    }

    private static AcoesDoJarvisFerramenta ferramenta(DiarioDeAcoes diario) {
        return new AcoesDoJarvisFerramenta(diario, JSON);
    }

    private static ObjectNode entrada(String periodo, String assunto) {
        ObjectNode no = JSON.createObjectNode();
        if (periodo != null) no.put("periodo", periodo);
        if (assunto != null) no.put("assunto", assunto);
        return no;
    }

    @Test
    @DisplayName("sem período, fala o de hoje, com a hora e a descrição de cada ação")
    void hojeEhOPadrao() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, null));

        assertTrue(fala.startsWith("Hoje: 1 ação."), fala);
        assertTrue(fala.contains("editei nota.md"), fala);
        assertTrue(fala.contains("12:00"), "a hora sai no fuso de São Paulo, não no UTC: " + fala);
    }

    @Test
    @DisplayName("ação aprovada vem marcada, para o dono saber que foi ele que autorizou")
    void aprovadaVemMarcada() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.ROTINA, "Executar rotina backup", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        // Apagar virou "faz e avisa": vai para a lixeira e volta sozinho, então a linha dele não
        // pode dizer "aprovado" — o dono não autorizou nada.
        diario.registrar(TipoDeAcao.APAGAR_ARQUIVO, "apaguei velho.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, null));

        assertTrue(fala.contains("Executar rotina backup (aprovado)"), fala);
        assertFalse(fala.contains("editei nota.md (aprovado)"),
                "edição é feita sem perguntar; marcá-la de aprovada seria mentira: " + fala);
        assertFalse(fala.contains("apaguei velho.md (aprovado)"),
                "apagar um arquivo também não pergunta mais: " + fala);
    }

    @Test
    @DisplayName("'ontem' lê o dia anterior e não o de hoje")
    void ontemLeODiaAnterior() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei de-ontem.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        relogio.avancar(Duration.ofDays(1));
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei de-hoje.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada("ontem", null));

        assertTrue(fala.startsWith("Ontem: 1 ação."), fala);
        assertTrue(fala.contains("de-ontem.md"), fala);
        assertFalse(fala.contains("de-hoje.md"), fala);
    }

    @Test
    @DisplayName("'semana' junta os dias e data cada linha, senão as horas se confundem")
    void semanaJuntaOsDias() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei antigo.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();
        relogio.avancar(Duration.ofDays(2));
        diario.registrar(TipoDeAcao.CRIAR_ARQUIVO, "criei novo.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada("semana", null));

        assertTrue(fala.startsWith("Últimos 7 dias: 2 ações."), fala);
        assertTrue(fala.contains("15/10"), "num período de vários dias a data tem de aparecer: " + fala);
        assertTrue(fala.contains("17/10"), fala);
    }

    @Test
    @DisplayName("assunto 'arquivos' responde 'o que mudou nos meus arquivos' sem o resto")
    void filtroDeArquivos() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei \"stack\"", OrigemDaAcao.ANOTADOR);
        diario.registrar(TipoDeAcao.ROTINA, "Executar rotina backup", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, "arquivos"));

        assertTrue(fala.startsWith("Hoje: 1 ação."), fala);
        assertTrue(fala.contains("editei nota.md"), fala);
        assertFalse(fala.contains("stack"), fala);
        assertFalse(fala.contains("backup"), fala);
    }

    @Test
    @DisplayName("assunto 'memoria' traz o que foi anotado e corrigido")
    void filtroDeMemoria() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.ANOTAR_MEMORIA, "anotei \"stack\"", OrigemDaAcao.ANOTADOR);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, "memoria"));

        assertTrue(fala.contains("stack"), fala);
        assertFalse(fala.contains("nota.md"), fala);
    }

    @Test
    @DisplayName("dia sem nada responde uma frase, não uma lista vazia")
    void diaVazioRespondeFrase() {
        var diario = diario(new RelogioFalso("2026-10-15T15:00:00Z"));

        assertEquals("Não mexi em nada hoje.", ferramenta(diario).executar(entrada(null, null)));
        assertEquals("Não mexi em nada nos arquivos ontem.",
                ferramenta(diario).executar(entrada("ontem", "arquivos")));
        assertEquals("Não mexi em nada na memória nos últimos 7 dias.",
                ferramenta(diario).executar(entrada("semana", "memoria")));
    }

    @Test
    @DisplayName("filtro que não casa nada responde a frase do assunto, não a do dia inteiro")
    void filtroSemResultadoRespondeFrase() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei nota.md", OrigemDaAcao.TELEGRAM);
        diario.aguardar();

        assertEquals("Não mexi em nada na memória hoje.",
                ferramenta(diario).executar(entrada(null, "memoria")));
    }

    @Test
    @DisplayName("a lista tem teto e o excedente vira 'E mais N': isto é lido em voz alta")
    void aListaTemTeto() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        for (int i = 0; i < 11; i++) {
            diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei arquivo-" + i + ".md",
                    OrigemDaAcao.TELEGRAM);
        }
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, null));

        assertTrue(fala.startsWith("Hoje: 11 ações."), fala);
        assertTrue(fala.contains("E mais 3."), "11 ações, 8 linhas, sobram 3: " + fala);
    }

    @Test
    @DisplayName("dia truncado avisa que a lista está incompleta")
    void diaTruncadoAvisa() {
        var relogio = new RelogioFalso("2026-10-15T15:00:00Z");
        var diario = diario(relogio);
        for (int i = 0; i < 502; i++) {
            diario.registrar(TipoDeAcao.EDICAO_ARQUIVO, "editei arquivo-" + i + ".md",
                    OrigemDaAcao.TELEGRAM);
        }
        diario.aguardar();

        String fala = ferramenta(diario).executar(entrada(null, null));

        assertTrue(fala.contains("a lista está incompleta"),
                "um dia que não cabe no diário tem de dizer isso: " + fala);
    }

    @Test
    @DisplayName("a descrição orienta o modelo e o afasta das ferramentas vizinhas")
    void descricaoOrientaOModelo() {
        var ferramenta = ferramenta(diario(new RelogioFalso()));

        assertEquals("acoes_do_jarvis", ferramenta.nome());
        String descricao = ferramenta.descricao();
        assertTrue(descricao.contains("o que você fez hoje?"), descricao);
        assertTrue(descricao.contains("custo_do_jarvis"),
                "sem a desambiguação o modelo confunde diário com gasto");
        assertTrue(descricao.contains("listar_memorias"), descricao);

        var esquema = ferramenta.esquemaEntrada();
        assertEquals("[\"hoje\",\"ontem\",\"semana\"]",
                esquema.path("properties").path("periodo").path("enum").toString(),
                "lista fechada: um inteiro de dias convidaria o modelo a pedir trinta arquivos");
        assertEquals("consultando o diário", ferramenta.progresso());
    }
}
