package dev.jarvis.memoria;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** O armazém dos procedimentos: o que ele aceita, o que recusa e o que vai ao prompt. */
class ProcedimentosServiceTest {
    private static final long DONO = 42L;
    @TempDir Path pasta;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock relogio = Clock.fixed(Instant.parse("2026-10-28T14:00:00Z"), ZoneId.of("UTC"));

    private ProcedimentosService servico() {
        return new ProcedimentosService(pasta.resolve("procedimentos"), json, relogio);
    }

    @Test void gravaLeEReescreve() throws Exception {
        var s = servico();
        var g = s.gravar(DONO, "proposta-de-site", "Proposta de site", "pedido de proposta comercial",
                "1. Ler o lead.\n2. Montar a proposta.");
        assertFalse(g.atualizado());
        var lido = s.ler(DONO, "proposta-de-site").orElseThrow();
        assertEquals("Proposta de site", lido.titulo());
        assertEquals("1. Ler o lead.\n2. Montar a proposta.", lido.corpo());
        assertEquals(1, lido.revisao());

        var de_novo = s.gravar(DONO, "proposta-de-site", "Proposta de site", "pedido de proposta comercial",
                "1. Ler o lead.\n2. Montar a proposta.\n3. Mandar em PDF.");
        assertTrue(de_novo.atualizado());
        assertEquals(2, s.ler(DONO, "proposta-de-site").orElseThrow().revisao());
        assertNull(s.gravar(DONO, "proposta-de-site", "Proposta de site", "pedido de proposta comercial",
                "1. Ler o lead.\n2. Montar a proposta.\n3. Mandar em PDF."), "igual não grava");
    }

    @Test void nomeNuncaViraCaminho() throws Exception {
        var s = servico();
        for (String nome : new String[] {"../fora", "Maiuscula", "a", "com espaco", "x/y"}) {
            assertThrows(IllegalArgumentException.class,
                    () -> s.gravar(DONO, nome, "t", "q", "corpo"), nome);
            assertTrue(s.ler(DONO, nome).isEmpty(), nome);
        }
        assertFalse(Files.exists(pasta.resolve("fora.md")));
    }

    @Test void recusaSegredoComValorMasNaoAPalavra() throws Exception {
        var s = servico();
        assertThrows(IllegalArgumentException.class,
                () -> s.gravar(DONO, "deploy", "Deploy", "subir a VPS", "Use token: abcdef123456 no header."));
        assertNotNull(s.gravar(DONO, "custo", "Custo", "pergunta de gasto",
                "1. Chamar custo_do_jarvis e falar o total de tokens."), "falar de token não é segredo");
    }

    @Test void indiceTemNomeEQuandoEOCorpoNao() throws Exception {
        var s = servico();
        s.gravar(DONO, "relatorio-de-vagas", "Relatório de vagas", "quando pedir vagas de estágio",
                "1. Buscar vagas.\n2. Filtrar Java.");
        String indice = s.indice(DONO);
        assertEquals("- relatorio-de-vagas: quando pedir vagas de estágio\n", indice);
        assertEquals("", s.indice(0));
        assertEquals("", s.indice(7), "outro usuário não vê");
    }

    @Test void esquecerTiraDeVistaSemApagar() throws Exception {
        var s = servico();
        s.gravar(DONO, "proposta-de-site", "Proposta de site", "proposta", "1. Passo.");
        assertEquals("Proposta de site", s.esquecer(DONO, "proposta-de-site").orElseThrow());
        assertTrue(s.listar(DONO).isEmpty());
        assertTrue(s.esquecer(DONO, "proposta-de-site").isEmpty());
        try (var removidos = Files.list(pasta.resolve("procedimentos").resolve("usuarios")
                .resolve(String.valueOf(DONO)).resolve(".removidos"))) {
            assertEquals(1, removidos.count(), "o arquivo fica guardado à parte");
        }
    }

    @Test void tetoDeProcedimentosValeSoParaNomeNovo() throws Exception {
        var s = servico();
        for (int i = 0; i < ProcedimentosService.LIMITE_PROCEDIMENTOS; i++) {
            s.gravar(DONO, "p" + String.format("%02d", i), "t", "q", "corpo " + i);
        }
        assertThrows(IllegalArgumentException.class, () -> s.gravar(DONO, "novo", "t", "q", "c"));
        assertNotNull(s.gravar(DONO, "p00", "t", "q", "corpo reescrito"), "reescrever continua valendo");
    }
}
