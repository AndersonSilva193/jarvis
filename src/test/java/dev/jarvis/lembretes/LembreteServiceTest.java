package dev.jarvis.lembretes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.jarvis.config.JarvisProperties;
import dev.jarvis.lembretes.LembreteService.Lembrete;
import tools.jackson.databind.json.JsonMapper;

/** Persistência, IDs que não voltam, atrasados da parada e cancelamento de série. */
class LembreteServiceTest {

    private static final ZoneId ZONA = ZoneId.of("America/Sao_Paulo");

    @TempDir
    Path pasta;

    private final JsonMapper json = JsonMapper.builder().build();
    private final JarvisProperties props = new JarvisProperties("Dono", "America/Sao_Paulo", null,
            new JarvisProperties.Telegram("token-de-teste", 99L),
            null, null, null, null, null, null, null, null, null);

    private RelogioFalso relogio;
    private TelegramFalso telegram;
    private Path arquivo;

    @BeforeEach
    void preparar() {
        // 2026-10-19T12:00Z = 09:00 em São Paulo (UTC-3).
        relogio = new RelogioFalso("2026-10-19T12:00:00Z");
        telegram = new TelegramFalso(props);
        arquivo = pasta.resolve("lembretes.json");
    }

    /** Um "restart do container": serviço novo, mesmo arquivo, mesmo relógio. */
    private LembreteService subir() {
        LembreteService servico = new LembreteService(telegram, props, json, arquivo, relogio);
        servico.recarregar();
        return servico;
    }

    private static ZonedDateTime em(String local) {
        return LocalDateTime.parse(local).atZone(ZONA);
    }

    @Test
    @DisplayName("lembrete salvo volta depois do restart, com recorrência e tudo")
    void salvaERecarrega() {
        LembreteService antes = subir();
        antes.agendar("tomar remédio", em("2026-10-19T22:00"), Repeticao.diario());
        antes.agendar("pagar o aluguel", em("2026-11-05T10:00"));
        assertTrue(Files.exists(arquivo), "o arquivo de lembretes deveria ter sido criado");

        List<Lembrete> depois = subir().listar();
        assertEquals(2, depois.size());
        assertEquals("tomar remédio", depois.get(0).texto());
        assertEquals(em("2026-10-19T22:00"), depois.get(0).quando());
        assertEquals(Recorrencia.DIARIO, depois.get(0).repeticao().tipo());
        assertEquals("pagar o aluguel", depois.get(1).texto());
        assertFalse(depois.get(1).recorrente());
    }

    @Test
    @DisplayName("os IDs continuam do maior salvo e nunca voltam a 1")
    void idsNaoVoltam() {
        LembreteService antes = subir();
        antes.agendar("primeiro", em("2026-10-19T20:00"));
        int segundo = antes.agendar("segundo", em("2026-10-19T21:00")).id();
        antes.cancelar(segundo);

        LembreteService depois = subir();
        // Com um cancelado, o maior ID pendente é 1 — mas o contador salvo é 3.
        assertEquals(3, depois.agendar("terceiro", em("2026-10-19T23:00")).id());
    }

    @Test
    @DisplayName("arquivo principal corrompido cai no .bak")
    void corrompidoCaiNoBak() throws IOException {
        LembreteService antes = subir();
        antes.agendar("do backup", em("2026-10-20T08:00"));
        // A segunda gravação é que copia a versão anterior (só com "do backup") para o .bak.
        antes.agendar("só no principal", em("2026-10-20T09:00"));
        Path backup = pasta.resolve("lembretes.json.bak");
        assertTrue(Files.exists(backup), "a segunda gravação deveria ter criado o .bak");

        Files.writeString(arquivo, "{isso não é json", StandardCharsets.UTF_8);

        List<Lembrete> recuperados = subir().listar();
        assertEquals(1, recuperados.size());
        assertEquals("do backup", recuperados.get(0).texto());
    }

    @Test
    @DisplayName("um lembrete vencido na parada vira uma mensagem com a hora original")
    void umAtrasado() {
        LembreteService antes = subir();
        antes.agendar("ligar para o contador", em("2026-10-19T10:00"));

        relogio.avancar(Duration.ofHours(3));  // passou das 10h; agora são 12h locais
        telegram.mensagens.clear();
        LembreteService depois = subir();

        assertEquals(1, telegram.mensagens.size());
        assertTrue(telegram.mensagens.get(0).contains("Atrasado: ligar para o contador"),
                telegram.mensagens.get(0));
        assertTrue(telegram.mensagens.get(0).contains("10:00"), telegram.mensagens.get(0));
        assertTrue(depois.listar().isEmpty(), "lembrete único atrasado não fica pendente");
    }

    @Test
    @DisplayName("vários vencidos com menos de 24 h viram uma mensagem cada")
    void variosAtrasados() {
        LembreteService antes = subir();
        antes.agendar("primeiro", em("2026-10-19T10:00"));
        antes.agendar("segundo", em("2026-10-19T11:00"));

        relogio.avancar(Duration.ofHours(5));
        telegram.mensagens.clear();
        subir();

        assertEquals(2, telegram.mensagens.size());
        assertTrue(telegram.mensagens.get(0).contains("primeiro"), telegram.mensagens.get(0));
        assertTrue(telegram.mensagens.get(1).contains("segundo"), telegram.mensagens.get(1));
    }

    @Test
    @DisplayName("acima de 24 h de atraso sai um resumo agrupado, nunca um por um")
    void atrasadosDeMaisDeUmDia() {
        LembreteService antes = subir();
        antes.agendar("primeiro", em("2026-10-19T10:00"));
        antes.agendar("segundo", em("2026-10-19T11:00"));
        antes.agendar("terceiro", em("2026-10-19T12:00"));

        relogio.avancar(Duration.ofDays(3));
        telegram.mensagens.clear();
        subir();

        assertEquals(1, telegram.mensagens.size());
        String resumo = telegram.mensagens.get(0);
        assertTrue(resumo.contains("3 lembretes venceram enquanto eu estava fora"), resumo);
        assertTrue(resumo.contains("primeiro") && resumo.contains("segundo")
                && resumo.contains("terceiro"), resumo);
    }

    @Test
    @DisplayName("atraso curto e atraso longo no mesmo restart: individuais mais um resumo")
    void atrasoCurtoELongoJuntos() {
        LembreteService antes = subir();
        antes.agendar("antigo", em("2026-10-19T10:00"));
        antes.agendar("recente", em("2026-10-21T10:00"));

        relogio.avancar(Duration.ofDays(2).plusHours(2));  // 21/10 às 11h locais
        telegram.mensagens.clear();
        subir();

        assertEquals(2, telegram.mensagens.size());
        assertTrue(telegram.mensagens.get(0).contains("Atrasado: recente"), telegram.mensagens.get(0));
        assertTrue(telegram.mensagens.get(1).contains("1 lembrete venceu enquanto eu estava fora"),
                telegram.mensagens.get(1));
        assertTrue(telegram.mensagens.get(1).contains("antigo"), telegram.mensagens.get(1));
    }

    @Test
    @DisplayName("série recorrente que perdeu a volta é avisada e reagendada para o futuro")
    void recorrenteAtrasadoSegueNaProxima() {
        LembreteService antes = subir();
        antes.agendar("tomar remédio", em("2026-10-19T10:00"), Repeticao.diario());

        relogio.avancar(Duration.ofDays(2).plusHours(2));  // 21/10 às 11h locais
        telegram.mensagens.clear();
        LembreteService depois = subir();

        assertEquals(1, telegram.mensagens.size(), telegram.mensagens.toString());
        List<Lembrete> pendentes = depois.listar();
        assertEquals(1, pendentes.size());
        assertEquals(em("2026-10-22T10:00"), pendentes.get(0).quando());
        assertEquals(Recorrencia.DIARIO, pendentes.get(0).repeticao().tipo());
    }

    @Test
    @DisplayName("cancelar um recorrente apaga a série inteira, inclusive do arquivo")
    void cancelarRecorrenteApagaSerie() {
        LembreteService antes = subir();
        int id = antes.agendar("alongar", em("2026-10-19T18:00"),
                Repeticao.semanal(java.util.EnumSet.of(java.time.DayOfWeek.MONDAY))).id();

        assertTrue(antes.cancelar(id));
        assertTrue(antes.listar().isEmpty());
        assertFalse(antes.cancelar(id), "cancelar duas vezes não encontra mais nada");

        assertTrue(subir().listar().isEmpty(), "a série não pode voltar depois do restart");
    }
}
