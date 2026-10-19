package dev.jarvis.pc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import dev.jarvis.ia.Assistente;
import dev.jarvis.ia.Canal;
import dev.jarvis.conversa.OuvinteProgresso;
import dev.jarvis.telegram.TelegramClient;
import tools.jackson.databind.json.JsonMapper;

class TarefasPlanilhaServiceTest {
    @TempDir Path temporaria;
    @Test void autorizacaoPersisteERevogacaoImpedeEdicao() throws Exception {
        Path arquivo = temporaria.resolve("tarefas.json");
        var json = JsonMapper.builder().build();
        Clock relogio = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneId.of("UTC"));
        @SuppressWarnings("unchecked") ObjectProvider<Assistente> assistente = mock(ObjectProvider.class);
        TelegramClient telegram = mock(TelegramClient.class);
        var servico = new TarefasPlanilhaService(json, arquivo, ZoneId.of("America/Sao_Paulo"),
                relogio, assistente, telegram);
        String idArquivo = "11111111-1111-1111-1111-111111111111";
        var tarefa = servico.registrar(42, idArquivo, "dados.xlsx", "atualize o estoque",
                "0 0 8 * * MON-FRI", false);
        var carregado = new TarefasPlanilhaService(json, arquivo, ZoneId.of("America/Sao_Paulo"),
                relogio, assistente, telegram);
        assertTrue(carregado.autorizada(tarefa.id(), 42, idArquivo, "dados.xlsx", false));
        assertFalse(carregado.autorizada(tarefa.id(), 42, idArquivo, "outro.xlsx", false));
        assertFalse(carregado.autorizada(tarefa.id(), 42, idArquivo, "dados.xlsx", true));
        assertFalse(carregado.revogar(tarefa.id(), 43));
        assertTrue(carregado.revogar(tarefa.id(), 42));
        assertFalse(carregado.autorizada(tarefa.id(), 42, idArquivo, "dados.xlsx", false));
    }

    @Test void execucaoNaoRepeteMesmoAposReinicio() throws Exception {
        Path arquivo = temporaria.resolve("recorrentes.json");
        var json = JsonMapper.builder().build();
        AtomicReference<Instant> instante = new AtomicReference<>(Instant.parse("2026-10-19T12:00:10Z"));
        Clock relogio = new Clock() {
            @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return instante.get(); }
        };
        @SuppressWarnings("unchecked") ObjectProvider<Assistente> provedor = mock(ObjectProvider.class);
        Assistente assistente = mock(Assistente.class);
        when(provedor.getObject()).thenReturn(assistente);
        when(assistente.executarTarefa(anyString(), eq(Canal.TAREFA), any(OuvinteProgresso.class)))
                .thenReturn("concluído");
        TelegramClient telegram = mock(TelegramClient.class);
        var servico = new TarefasPlanilhaService(json, arquivo, ZoneId.of("UTC"),
                relogio, provedor, telegram);
        servico.registrar(42, "11111111-1111-1111-1111-111111111111", "dados.xlsx",
                "atualize", "0 * * * * *", false);
        instante.set(Instant.parse("2026-10-19T12:01:00Z"));
        servico.executarPendentes();
        verify(assistente, timeout(2000).times(1)).executarTarefa(anyString(),
                eq(Canal.TAREFA), any(OuvinteProgresso.class));
        servico.executarPendentes();
        var reiniciado = new TarefasPlanilhaService(json, arquivo, ZoneId.of("UTC"),
                relogio, provedor, telegram);
        reiniciado.executarPendentes();
        verify(assistente, after(200).times(1)).executarTarefa(anyString(),
                eq(Canal.TAREFA), any(OuvinteProgresso.class));
    }
}
