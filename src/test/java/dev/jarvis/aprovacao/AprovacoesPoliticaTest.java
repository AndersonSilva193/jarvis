package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.Aprovacoes.Risco;
import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.autonomia.Nivel;
import dev.jarvis.autonomia.PoliticaDeAutonomia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/**
 * O {@code encaminhar}: a política decide, o {@code Aprovacoes} cumpre, e o diário diz se houve
 * pergunta.
 */
class AprovacoesPoliticaTest {

    @TempDir
    Path pasta;

    private final RelogioFalso relogio = new RelogioFalso("2026-10-15T15:00:00Z");
    private final AtomicInteger execucoes = new AtomicInteger();
    private final JsonMapper json = JsonMapper.builder().build();
    private DiarioDeAcoes diario;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private static JarvisProperties props() {
        return new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
    }

    private Aprovacoes com(String autonomia) throws Exception {
        Path arquivo = pasta.resolve("autonomia.md");
        Files.writeString(arquivo, autonomia, StandardCharsets.UTF_8);
        diario = new DiarioDeAcoes(props(), json, relogio, pasta.resolve("diario"));
        return new Aprovacoes(relogio, diario, new PoliticaDeAutonomia(arquivo));
    }

    private Aprovacoes.Encaminhamento rotina(Aprovacoes aprovacoes, String nome) {
        return aprovacoes.encaminhar(AcaoAutonoma.ROTINA_RODAR, nome, "rotina",
                "Executar rotina " + nome, "Vou rodar a rotina " + nome + ". Posso?", Risco.COMUM,
                42, "pedido-1", "telegram",
                Aprovacoes.frase(() -> "rodou " + execucoes.incrementAndGet()));
    }

    private List<AcaoRegistrada> registradas() {
        diario.aguardar();
        return diario.doDia(LocalDate.parse("2026-10-15")).acoes();
    }

    @Test
    @DisplayName("faz e avisa: roda na hora, sem pendência, e o diário anota como feito sem pergunta")
    void fazEAvisaRodaSemPendencia() throws Exception {
        var aprovacoes = com("## faz_e_avisa\n- rotina.rodar:backup_notas\n");

        var resultado = rotina(aprovacoes, "backup_notas");

        assertEquals(Nivel.FAZ_E_AVISA, resultado.nivel());
        assertEquals("rodou 1", resultado.texto());
        assertNull(resultado.confirmacao());
        assertTrue(aprovacoes.pendenciaDe(42, "pedido-2").isEmpty(), "nada ficou esperando um sim");

        var acoes = registradas();
        assertEquals(1, acoes.size());
        assertEquals(TipoDeAcao.ROTINA, acoes.getFirst().tipo());
        assertEquals(Boolean.FALSE, acoes.getFirst().perguntada());
        assertTrue(acoes.getFirst().semPergunta(), "entra na seção 'o que fiz sozinho'");
        assertEquals(1, diario.deHoje().autonomas().size());
    }

    @Test
    @DisplayName("pergunta antes: nada roda até o sim, e aí o diário anota como aprovado")
    void perguntaAbrePendencia() throws Exception {
        var aprovacoes = com("## faz_e_avisa\n- rotina.rodar:backup_notas\n");

        var resultado = rotina(aprovacoes, "deploy");

        assertEquals(Nivel.PERGUNTA, resultado.nivel());
        assertNotNull(resultado.confirmacao());
        assertEquals("Vou rodar a rotina deploy. Posso?", resultado.texto());
        assertEquals(0, execucoes.get());

        assertEquals("rodou 1", aprovacoes.executarPendente(42, "pedido-2", "telegram").orElseThrow());
        var acoes = registradas();
        assertEquals(1, acoes.size());
        assertEquals(Boolean.TRUE, acoes.getFirst().perguntada());
        assertFalse(acoes.getFirst().semPergunta());
    }

    @Test
    @DisplayName("proibido: recusa curta, nada roda, nada fica pendente, nada vai ao diário")
    void proibidoRecusa() throws Exception {
        var aprovacoes = com("## proibido\n- rotina.rodar\n");

        var resultado = rotina(aprovacoes, "backup_notas");

        assertEquals(Nivel.PROIBIDO, resultado.nivel());
        assertNull(resultado.confirmacao());
        assertTrue(resultado.texto().startsWith("Não posso rodar a rotina backup_notas"),
                resultado.texto());
        assertEquals(0, execucoes.get());
        assertTrue(aprovacoes.pendenciaDe(42, "pedido-2").isEmpty());
        assertEquals(List.of(), registradas());
    }

    @Test
    @DisplayName("o piso vale também aqui: desligar com faz_e_avisa no arquivo ainda pergunta")
    void pisoNoEncaminhar() throws Exception {
        var aprovacoes = com("## faz_e_avisa\n- pc.desligar\n");

        var resultado = aprovacoes.encaminhar(AcaoAutonoma.PC_DESLIGAR, null,
                Aprovacoes.TIPO_PC_ENERGIA, "desligar o PC", "Vou desligar o PC. Posso?",
                Risco.CRITICO, 42, "pedido-1", "telegram",
                Aprovacoes.frase(() -> "desligou " + execucoes.incrementAndGet()));

        assertEquals(Nivel.PERGUNTA, resultado.nivel());
        assertEquals(0, execucoes.get());
    }

    @Test
    @DisplayName("sem usuário autenticado, nem o faz e avisa roda")
    void exigeUsuarioAutenticado() throws Exception {
        var aprovacoes = com("## faz_e_avisa\n- memoria.esquecer\n");
        assertThrows(IllegalArgumentException.class, () -> aprovacoes.encaminhar(
                AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria", "esquecer x", "Posso?",
                Risco.COMUM, 0, "pedido-1", "telegram", Aprovacoes.frase(() -> "apagou")));
    }

    @Test
    @DisplayName("o 'perguntada' sobrevive ao disco, e linha antiga sem o campo segue o tipo")
    void perguntadaNoDisco() throws Exception {
        Path pastaDoDiario = pasta.resolve("diario");
        Files.createDirectories(pastaDoDiario);
        // Um dia gravado por uma versão anterior: rotina (aprovada pelo tipo) e edição (autônoma).
        Files.writeString(pastaDoDiario.resolve("2026-10-15.json"), """
                {"dia":"2026-10-15","fuso":"America/Sao_Paulo","descartadas":0,"acoes":[
                  {"instante":"2026-10-15T14:00:00Z","tipo":"rotina","descricao":"Executar rotina velha","origem":"telegram","id":"a1"},
                  {"instante":"2026-10-15T14:01:00Z","tipo":"edicao_arquivo","descricao":"editei","origem":"telegram","id":"a2"}
                ]}""", StandardCharsets.UTF_8);
        var aprovacoes = com("## faz_e_avisa\n- rotina.rodar:backup_notas\n");
        rotina(aprovacoes, "backup_notas");
        diario.aguardar();
        diario.fechar();

        diario = new DiarioDeAcoes(props(), json, relogio, pastaDoDiario);
        var acoes = diario.doDia(LocalDate.parse("2026-10-15")).acoes();
        assertEquals(3, acoes.size());
        assertNull(acoes.get(0).perguntada());
        assertFalse(acoes.get(0).semPergunta(), "rotina antiga continua '(aprovado)'");
        assertTrue(acoes.get(1).semPergunta(), "edição antiga continua autônoma");
        assertEquals(Boolean.FALSE, acoes.get(2).perguntada());
        assertTrue(acoes.get(2).semPergunta(), "a rotina liberada pela política voltou do disco como feita sozinha");
    }
}
