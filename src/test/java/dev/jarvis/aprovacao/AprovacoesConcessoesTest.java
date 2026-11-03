package dev.jarvis.aprovacao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.aprovacao.Aprovacoes.Risco;
import dev.jarvis.autonomia.AcaoAutonoma;
import dev.jarvis.autonomia.Concessao;
import dev.jarvis.autonomia.Concessoes;
import dev.jarvis.autonomia.Nivel;
import dev.jarvis.autonomia.PoliticaDeAutonomia;
import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** A permissão aprendida: a oferta na terceira aprovação, o "sim" que a grava e o "não" que a cala. */
class AprovacoesConcessoesTest {

    @TempDir
    Path pasta;

    private final RelogioFalso relogio = new RelogioFalso("2026-11-03T15:00:00Z");
    private final AtomicInteger execucoes = new AtomicInteger();
    private final JsonMapper json = JsonMapper.builder().build();
    private DiarioDeAcoes diario;
    private Concessoes concessoes;
    private int pedido;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private Aprovacoes com(String autonomia) throws Exception {
        var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                new JarvisProperties.Diario(null));
        diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        concessoes = new Concessoes(json, relogio, ZoneId.of("America/Sao_Paulo"),
                pasta.resolve(Concessoes.NOME_DO_ARQUIVO), diario);
        Path arquivo = pasta.resolve("autonomia.md");
        Files.writeString(arquivo, autonomia, StandardCharsets.UTF_8);
        return new Aprovacoes(relogio, diario, new PoliticaDeAutonomia(arquivo, concessoes));
    }

    private Aprovacoes.Encaminhamento encaminhar(Aprovacoes a, AcaoAutonoma acao, String alvo, String tipo) {
        return a.encaminhar(acao, alvo, tipo, "Executar " + acao.oQue(alvo),
                "Vou " + acao.oQue(alvo) + ". Posso?", Risco.COMUM, 42, "pedido-" + (++pedido),
                "telegram", Aprovacoes.frase(() -> "feito " + execucoes.incrementAndGet()));
    }

    /** Pede e aprova com um "sim": devolve a resposta do "sim". */
    private String aprovar(Aprovacoes a, AcaoAutonoma acao, String alvo, String tipo) {
        assertEquals(Nivel.PERGUNTA, encaminhar(a, acao, alvo, tipo).nivel());
        return a.executarPendente(42, "resposta-" + (++pedido), "telegram").orElseThrow();
    }

    private List<TipoDeAcao> tiposNoDiario() {
        diario.aguardar();
        return diario.periodo(2).acoes().stream().map(AcaoRegistrada::tipo).toList();
    }

    @Test
    @DisplayName("na terceira aprovação a resposta oferece parar de perguntar; na segunda, não")
    void ofertaNaTerceira() throws Exception {
        var a = com("");
        assertEquals("feito 1", aprovar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina"));
        assertEquals("feito 2", aprovar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina"));
        assertTrue(a.pendenciaDe(42, "outra-mensagem").isEmpty(), "na segunda, nenhuma oferta aberta");

        String terceira = aprovar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina");
        assertEquals("feito 3\n\nÉ a terceira vez que você aprova rodar a rotina backup. Quer que eu "
                + "pare de perguntar?", terceira);
        assertEquals("Quer que eu pare de perguntar antes de rodar a rotina backup?",
                a.pendenciaDe(42, "outra-mensagem").orElseThrow());
    }

    @Test
    @DisplayName("SIM à oferta grava concessão PERMANENTE; a próxima vez roda sem perguntar e o primeiro uso vai ao diário")
    void simGravaPermanente() throws Exception {
        var a = com("");
        for (int i = 0; i < 3; i++) aprovar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina");

        String sim = a.executarPendente(42, "sim-a-oferta", "telegram").orElseThrow();
        assertTrue(sim.startsWith("Combinado: não pergunto mais antes de rodar a rotina backup."), sim);
        Concessao c = concessoes.ativas().getFirst();
        assertEquals(Concessao.Escopo.PERMANENTE, c.escopo());
        assertEquals(Concessao.Origem.APRENDIDA, c.origem());
        assertEquals("backup", c.alvo());

        var livre = encaminhar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina");
        assertEquals(Nivel.FAZ_E_AVISA, livre.nivel());
        encaminhar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina");
        assertEquals(Nivel.PERGUNTA, encaminhar(a, AcaoAutonoma.ROTINA_RODAR, "outra", "rotina").nivel(),
                "a permissão é daquela rotina");
        List<TipoDeAcao> tipos = tiposNoDiario();
        assertEquals(1, tipos.stream().filter(t -> t == TipoDeAcao.CONCESSAO_CRIADA).count());
        assertEquals(1, tipos.stream().filter(t -> t == TipoDeAcao.CONCESSAO_USADA).count());
    }

    @Test
    @DisplayName("NÃO à oferta: não oferece de novo por 30 dias")
    void naoBloqueiaAOferta() throws Exception {
        var a = com("");
        for (int i = 0; i < 3; i++) aprovar(a, AcaoAutonoma.PLANILHA_AGENDAR, null, "tarefa_planilha");
        assertEquals(Aprovacoes.Cancelamento.CANCELADA, a.recusarPendente(42, "nao-a-oferta"));
        assertEquals(List.of(), concessoes.ativas());

        for (int i = 0; i < 6; i++) {
            assertFalse(aprovar(a, AcaoAutonoma.PLANILHA_AGENDAR, null, "tarefa_planilha")
                    .contains("terceira vez"));
        }
        relogio.avancar(Duration.ofDays(30));
        assertTrue(aprovar(a, AcaoAutonoma.PLANILHA_AGENDAR, null, "tarefa_planilha")
                .contains("terceira vez"), "passados os 30 dias, a sequência já estava completa");
    }

    @Test
    @DisplayName("um NÃO a uma pendência comum zera a sequência; a frase 'cancelar' conta como NÃO")
    void naoZeraASequencia() throws Exception {
        var a = com("");
        aprovar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria");
        aprovar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria");
        encaminhar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria");
        assertEquals("Operação cancelada.", a.tratar("cancelar", 42, "resposta-nao", "telegram").orElseThrow());
        assertFalse(aprovar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria").contains("terceira vez"));
        assertFalse(aprovar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria").contains("terceira vez"));
        assertTrue(aprovar(a, AcaoAutonoma.MEMORIA_ESQUECER, null, "memoria").contains("terceira vez"));
    }

    @Test
    @DisplayName("piso CRITICO: desligar aprovado cinco vezes nunca oferece parar de perguntar")
    void nuncaOfereceCritico() throws Exception {
        var a = com("");
        for (int i = 0; i < 5; i++) {
            String resposta = aprovar(a, AcaoAutonoma.PC_DESLIGAR, null, Aprovacoes.TIPO_PC_ENERGIA);
            assertFalse(resposta.contains("terceira vez"), resposta);
        }
        assertTrue(a.pendenciaDe(42, "depois").isEmpty());
    }

    @Test
    @DisplayName("com linha no autonomia.md a oferta não aparece: a concessão não teria efeito")
    void arquivoTravaAOferta() throws Exception {
        var a = com("## pergunta_antes\n- arquivo.apagar_lote\n");
        for (int i = 0; i < 3; i++) {
            assertFalse(aprovar(a, AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null, Aprovacoes.TIPO_PC_LIXEIRA)
                    .contains("terceira vez"));
        }
    }

    @Test
    @DisplayName("permissão revogada volta a perguntar")
    void revogadaVoltaAPerguntar() throws Exception {
        var a = com("");
        concessoes.conceder(AcaoAutonoma.ROTINA_RODAR, null, Concessao.Escopo.ATE,
                relogio.instant().plus(Duration.ofHours(3)), null, Concessao.Origem.TEMPORARIA,
                "pode rodar", 42, "telegram");
        assertEquals(Nivel.FAZ_E_AVISA, encaminhar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina").nivel());
        concessoes.revogar(AcaoAutonoma.ROTINA_RODAR, null, 42, "telegram");
        assertEquals(Nivel.PERGUNTA, encaminhar(a, AcaoAutonoma.ROTINA_RODAR, "backup", "rotina").nivel());
    }
}
