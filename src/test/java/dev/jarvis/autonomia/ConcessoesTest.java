package dev.jarvis.autonomia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.diario.AcaoRegistrada;
import dev.jarvis.diario.DiarioDeAcoes;
import dev.jarvis.diario.TipoDeAcao;
import dev.jarvis.pc.RelogioFalso;
import tools.jackson.databind.json.JsonMapper;

/** O estado gravado das concessões: prazo, contagem de aprovações, oferta, revogação e disco. */
class ConcessoesTest {

    @TempDir
    Path pasta;

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");
    /** Terça, 03/11/2026, 12:00 em São Paulo. */
    private final RelogioFalso relogio = new RelogioFalso("2026-11-03T15:00:00Z");
    private final JsonMapper json = JsonMapper.builder().build();
    private DiarioDeAcoes diario;

    @AfterEach
    void fechar() {
        if (diario != null) diario.fechar();
    }

    private Concessoes nova() {
        if (diario == null) {
            var props = new JarvisProperties("Anderson", "America/Sao_Paulo", "", null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null, null, null,
                    new JarvisProperties.Diario(null));
            diario = new DiarioDeAcoes(props, json, relogio, pasta.resolve("diario"));
        }
        return new Concessoes(json, relogio, SP, pasta.resolve(Concessoes.NOME_DO_ARQUIVO), diario);
    }

    private List<TipoDeAcao> tiposNoDiario() {
        diario.aguardar();
        return diario.periodo(2).acoes().stream().map(AcaoRegistrada::tipo).toList();
    }

    private Concessao ateDaqui(Concessoes c, AcaoAutonoma acao, String alvo, Duration quanto)
            throws Exception {
        return c.conceder(acao, alvo, Concessao.Escopo.ATE, relogio.instant().plus(quanto), null,
                Concessao.Origem.TEMPORARIA, "pode rodar", 42, "telegram");
    }

    @Test
    @DisplayName("concessão com prazo vale até o instante e, com o relógio passando dele, expira e vai ao diário")
    void expiraComRelogioFixo() throws Exception {
        var c = nova();
        ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, null, Duration.ofHours(2));
        assertTrue(c.vigente(AcaoAutonoma.ROTINA_RODAR, "backup").isPresent());

        relogio.avancar(Duration.ofHours(2).minusSeconds(1));
        assertTrue(c.vigente(AcaoAutonoma.ROTINA_RODAR, "backup").isPresent(), "um segundo antes ainda vale");

        relogio.avancar(Duration.ofSeconds(1));
        assertTrue(c.vigente(AcaoAutonoma.ROTINA_RODAR, "backup").isEmpty(), "no instante do prazo já não vale");
        assertEquals(List.of(), c.ativas());
        assertEquals(List.of(TipoDeAcao.CONCESSAO_CRIADA, TipoDeAcao.CONCESSAO_EXPIRADA), tiposNoDiario());

        c.expurgar();
        assertEquals(2, tiposNoDiario().size(), "a expiração entra no diário uma vez só");
    }

    @Test
    @DisplayName("sobrevive ao restart: o arquivo relido devolve a mesma concessão")
    void persisteNoDisco() throws Exception {
        var c = nova();
        Concessao gravada = ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, "backup", Duration.ofDays(1));
        var relida = nova();
        assertEquals(List.of(gravada), relida.ativas());
        assertTrue(Files.exists(pasta.resolve(Concessoes.NOME_DO_ARQUIVO)));
    }

    @Test
    @DisplayName("piso CRITICO: desligar, reiniciar e computer use não se concedem nem pelo JSON editado à mão")
    void pisoCritico() throws Exception {
        var c = nova();
        for (AcaoAutonoma acao : List.of(AcaoAutonoma.PC_DESLIGAR, AcaoAutonoma.PC_REINICIAR,
                AcaoAutonoma.COMPUTER_USE_INICIAR)) {
            assertThrows(IllegalArgumentException.class,
                    () -> ateDaqui(c, acao, null, Duration.ofHours(1)), acao.chave());
        }
        Files.writeString(pasta.resolve(Concessoes.NOME_DO_ARQUIVO), """
                {"concessoes":[{"id":"x","chave":"pc.desligar","escopo":"permanente",
                "origem":"temporaria","evidencia":"pode","criadaEm":"2026-11-03T15:00:00Z"}]}""",
                StandardCharsets.UTF_8);
        var editada = nova();
        assertEquals(List.of(), editada.ativas());
        assertTrue(editada.vigente(AcaoAutonoma.PC_DESLIGAR, null).isEmpty());
    }

    @Test
    @DisplayName("a oferta vem na terceira aprovação seguida, e não na segunda")
    void ofertaNaTerceira() {
        var c = nova();
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ROTINA_RODAR, "backup"));
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ROTINA_RODAR, "backup"));
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ROTINA_RODAR, "outra"), "outro alvo conta à parte");
        assertTrue(c.registrarAprovacao(AcaoAutonoma.ROTINA_RODAR, "backup"));
    }

    @Test
    @DisplayName("um NÃO no meio zera a sequência; aprovação de mais de 30 dias não conta")
    void naoZeraEJanela() {
        var c = nova();
        c.registrarAprovacao(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null);
        c.registrarAprovacao(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null);
        c.registrarRecusa(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null);
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null));
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null));
        relogio.avancar(Duration.ofDays(31));
        assertFalse(c.registrarAprovacao(AcaoAutonoma.ARQUIVO_APAGAR_LOTE, null),
                "as duas de antes saíram da janela");
    }

    @Test
    @DisplayName("NÃO à oferta: não oferece de novo por 30 dias, e volta a oferecer depois")
    void naoBloqueiaPorTrintaDias() {
        var c = nova();
        for (int i = 0; i < 3; i++) c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null);
        c.registrarOferta(AcaoAutonoma.PLANILHA_AGENDAR, null);
        c.recusarOferta(AcaoAutonoma.PLANILHA_AGENDAR, null);

        relogio.avancar(Duration.ofDays(29));
        for (int i = 0; i < 2; i++) c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null);
        assertFalse(c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null), "dentro dos 30 dias");

        relogio.avancar(Duration.ofDays(1));
        assertTrue(c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null), "passados os 30 dias");
    }

    @Test
    @DisplayName("oferta ignorada volta só depois de mais três aprovações")
    void ofertaIgnoradaVoltaDepoisDeTres() {
        var c = nova();
        for (int i = 0; i < 3; i++) c.registrarAprovacao(AcaoAutonoma.MEMORIA_ESQUECER, null);
        c.registrarOferta(AcaoAutonoma.MEMORIA_ESQUECER, null);
        assertFalse(c.registrarAprovacao(AcaoAutonoma.MEMORIA_ESQUECER, null));
        assertFalse(c.registrarAprovacao(AcaoAutonoma.MEMORIA_ESQUECER, null));
        assertTrue(c.registrarAprovacao(AcaoAutonoma.MEMORIA_ESQUECER, null));
    }

    @Test
    @DisplayName("piso CRITICO: nem dez aprovações seguidas fazem oferecer desligar o PC")
    void nuncaOfereceCritico() {
        var c = nova();
        for (int i = 0; i < 10; i++) {
            assertFalse(c.registrarAprovacao(AcaoAutonoma.PC_DESLIGAR, null));
            assertFalse(c.registrarAprovacao(AcaoAutonoma.PC_REINICIAR, null));
        }
    }

    @Test
    @DisplayName("revogar com alvo leva também a permissão da ação inteira, anota no diário e cala a oferta")
    void revogacao() throws Exception {
        var c = nova();
        ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, null, Duration.ofDays(1));
        ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, "backup", Duration.ofDays(1));
        ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, "outra", Duration.ofDays(1));
        ateDaqui(c, AcaoAutonoma.PLANILHA_AGENDAR, null, Duration.ofDays(1));

        assertEquals(2, c.revogar(AcaoAutonoma.ROTINA_RODAR, "backup", 42, "telegram").size());
        assertTrue(c.vigente(AcaoAutonoma.ROTINA_RODAR, "backup").isEmpty());
        assertTrue(c.vigente(AcaoAutonoma.ROTINA_RODAR, "outra").isPresent());

        assertEquals(2, c.revogar(null, null, 42, "telegram").size(), "sem ação, todas");
        assertEquals(List.of(), c.ativas());
        assertEquals(4, tiposNoDiario().stream().filter(t -> t == TipoDeAcao.CONCESSAO_REVOGADA).count());

        for (int i = 0; i < 2; i++) c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null);
        assertFalse(c.registrarAprovacao(AcaoAutonoma.PLANILHA_AGENDAR, null),
                "acabou de pedir para voltar a perguntar");
    }

    @Test
    @DisplayName("o primeiro uso vai ao diário uma vez só")
    void primeiroUso() throws Exception {
        var c = nova();
        Concessao dada = ateDaqui(c, AcaoAutonoma.ROTINA_RODAR, null, Duration.ofDays(1));
        c.marcarUso(dada.id(), 42, "telegram");
        c.marcarUso(dada.id(), 42, "telegram");
        assertEquals(1, tiposNoDiario().stream().filter(t -> t == TipoDeAcao.CONCESSAO_USADA).count());
        assertTrue(nova().ativas().getFirst().usadaEm() != null, "o uso também fica no disco");
    }

    @Test
    @DisplayName("o prazo é dito em português, relativo a hoje no fuso do Jarvis")
    void prazoFalado() {
        var c = nova();
        Instant hoje = LocalDate.parse("2026-11-03").atTime(23, 59).atZone(SP).toInstant();
        Instant sexta = LocalDate.parse("2026-11-06").atTime(23, 59).atZone(SP).toInstant();
        assertEquals("hoje às 23:59", c.quando(hoje));
        assertEquals("amanhã às 23:59", c.quando(hoje.plus(Duration.ofDays(1))));
        assertEquals("sexta, 06/11, às 23:59", c.quando(sexta));
    }
}
