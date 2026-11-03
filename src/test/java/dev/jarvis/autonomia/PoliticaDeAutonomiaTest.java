package dev.jarvis.autonomia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PoliticaDeAutonomiaTest {

    @TempDir
    Path pasta;

    private Path arquivo() {
        return pasta.resolve(PoliticaDeAutonomia.NOME_DO_ARQUIVO);
    }

    /** Grava e empurra a data de modificação, para a recarga não depender da resolução do disco. */
    private PoliticaDeAutonomia com(String texto, long segundos) throws Exception {
        Files.writeString(arquivo(), texto, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(arquivo(), FileTime.from(Instant.ofEpochSecond(1_700_000_000L + segundos)));
        return new PoliticaDeAutonomia(arquivo());
    }

    @Test
    @DisplayName("sem arquivo, toda ação do catálogo vale o padrão do código — que é perguntar")
    void semArquivoValemOsPadroes() {
        var politica = new PoliticaDeAutonomia(arquivo());
        for (AcaoAutonoma acao : AcaoAutonoma.values()) {
            assertEquals(acao.padrao(), politica.nivel(acao, null), acao.chave());
            assertEquals(Nivel.PERGUNTA, politica.nivel(acao, null), acao.chave());
        }
        assertEquals("", politica.resumo(), "nada difere do padrão, nada vai para o prompt");
    }

    @Test
    @DisplayName("cada seção dá o seu nível")
    void cadaNivel() throws Exception {
        var politica = com("""
                ## faz_e_avisa
                - rotina.rodar:backup_notas
                ## pergunta_antes
                - planilha.agendar
                ## proibido
                - memoria.esquecer
                """, 1);

        assertEquals(Nivel.FAZ_E_AVISA, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "backup_notas"));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "outra"),
                "o alvo libera só aquela rotina; as outras ficam no padrão");
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PLANILHA_AGENDAR, null));
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.MEMORIA_ESQUECER, null));
    }

    @Test
    @DisplayName("o arquivo não baixa o piso: desligar, reiniciar e computer use continuam perguntando")
    void pisoQueOArquivoNaoBaixa() throws Exception {
        var politica = com("""
                ## faz_e_avisa
                - pc.desligar
                - pc.reiniciar
                - computer_use.iniciar
                """, 1);

        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PC_DESLIGAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PC_REINICIAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.COMPUTER_USE_INICIAR, null));
        assertEquals(3, PoliticaDeAutonomia.ler(Files.readString(arquivo())).ignoradas().size(),
                "as três tentativas aparecem no WARN");
    }

    @Test
    @DisplayName("o piso só impede de afrouxar: proibir desligar vale")
    void pisoDeixaEndurecer() throws Exception {
        var politica = com("## proibido\n- pc.desligar\n", 1);
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.PC_DESLIGAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PC_REINICIAR, null));
    }

    @Test
    @DisplayName("chave desconhecida, alvo inválido e linha fora de seção são ignorados sem derrubar o resto")
    void chaveDesconhecidaIgnorada() throws Exception {
        String texto = """
                - planilha.agendar
                ## faz_e_avisa
                - pc.formatar
                - rotina.rodar:../../etc
                - planilha.agendar:qualquer
                - memoria.esquecer
                ## talvez
                - arquivo.apagar_lote
                """;
        var politica = com(texto, 1);

        assertEquals(Nivel.FAZ_E_AVISA, politica.nivel(AcaoAutonoma.MEMORIA_ESQUECER, null),
                "a linha boa vale mesmo cercada de linhas ruins");
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.PLANILHA_AGENDAR, null));
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null),
                "item sob seção desconhecida não cai na seção anterior");

        var ignoradas = PoliticaDeAutonomia.ler(texto).ignoradas();
        assertEquals(6, ignoradas.size(), ignoradas.toString());
        assertTrue(ignoradas.stream().anyMatch(l -> l.contains("chave desconhecida 'pc.formatar'")), ignoradas.toString());
        assertTrue(ignoradas.stream().anyMatch(l -> l.contains("alvo inválido")), ignoradas.toString());
        assertTrue(ignoradas.stream().anyMatch(l -> l.contains("não aceita alvo")), ignoradas.toString());
        assertTrue(ignoradas.stream().anyMatch(l -> l.contains("seção desconhecida 'talvez'")), ignoradas.toString());
    }

    @Test
    @DisplayName("a linha com alvo vence a linha sem alvo, nos dois sentidos")
    void alvoVenceGeral() throws Exception {
        var politica = com("""
                ## faz_e_avisa
                - rotina.rodar:backup_notas
                ## proibido
                - rotina.rodar
                """, 1);

        assertEquals(Nivel.FAZ_E_AVISA, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "backup_notas"));
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.ROTINA_RODAR, "deploy"));
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.ROTINA_RODAR, null));
    }

    @Test
    @DisplayName("a mesma ação em duas seções fica com a mais rigorosa")
    void duasSecoesValeAMaisRigorosa() throws Exception {
        var politica = com("""
                ## proibido
                - planilha.agendar
                ## faz_e_avisa
                - planilha.agendar
                """, 1);
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.PLANILHA_AGENDAR, null));
    }

    @Test
    @DisplayName("mudar o arquivo muda a decisão no pedido seguinte; apagar volta aos padrões")
    void recargaAoMudarOArquivo() throws Exception {
        var politica = com("## faz_e_avisa\n- memoria.esquecer\n", 1);
        assertEquals(Nivel.FAZ_E_AVISA, politica.nivel(AcaoAutonoma.MEMORIA_ESQUECER, null));

        Files.writeString(arquivo(), "## proibido\n- memoria.esquecer\n", StandardCharsets.UTF_8);
        Files.setLastModifiedTime(arquivo(), FileTime.from(Instant.ofEpochSecond(1_700_000_100L)));
        assertEquals(Nivel.PROIBIDO, politica.nivel(AcaoAutonoma.MEMORIA_ESQUECER, null));

        Files.delete(arquivo());
        assertEquals(Nivel.PERGUNTA, politica.nivel(AcaoAutonoma.MEMORIA_ESQUECER, null));
    }

    @Test
    @DisplayName("o resumo do prompt traz só o que difere do padrão")
    void resumoSoComODiferente() throws Exception {
        var politica = com("""
                ## faz_e_avisa
                - rotina.rodar:backup_notas
                ## pergunta_antes
                - planilha.agendar
                ## proibido
                - computer_use.iniciar
                """, 1);

        String resumo = politica.resumo();
        assertEquals("""
                - Faz e avisa, sem perguntar: rodar a rotina backup_notas.
                - Proibido pela configuração: usar a tela do PC (computer use).
                """, resumo);
        assertFalse(resumo.contains("planilha"), "pergunta_antes é o padrão dela: não vai");
    }

    @Test
    @DisplayName("minhas_permissoes fala em português corrido, com os proibidos fixos")
    void explicarEmLinguagemDeGente() throws Exception {
        var politica = com("""
                ## faz_e_avisa
                - rotina.rodar:backup_notas
                ## proibido
                - computer_use.iniciar
                """, 1);

        String texto = politica.explicar();
        assertTrue(texto.contains("Faço sozinho e aviso depois:"), texto);
        assertTrue(texto.contains("rodar a rotina backup_notas"), texto);
        assertTrue(texto.contains("rodar as outras rotinas no PC"), texto);
        assertTrue(texto.contains("Pergunto antes de:"), texto);
        assertTrue(texto.contains("desligar o PC"), texto);
        assertTrue(texto.contains("Nunca faço: usar a tela do PC (computer use); nem comando de shell"), texto);
        assertFalse(texto.contains("faz_e_avisa") || texto.contains("rotina.rodar"),
                "nada de chave do arquivo na fala: " + texto);
    }

    @Test
    @DisplayName("o config/autonomia.md do repositório é lido sem nenhuma linha ignorada e repete os padrões")
    void oArquivoDoRepositorio() throws Exception {
        Path doRepositorio = Path.of("config", PoliticaDeAutonomia.NOME_DO_ARQUIVO);
        var leitura = PoliticaDeAutonomia.ler(Files.readString(doRepositorio));
        assertEquals(java.util.List.of(), leitura.ignoradas());

        var politica = new PoliticaDeAutonomia(doRepositorio);
        for (AcaoAutonoma acao : AcaoAutonoma.values()) {
            assertEquals(acao.padrao(), politica.nivel(acao, null), acao.chave());
        }
        assertEquals("", politica.resumo());
    }
}
