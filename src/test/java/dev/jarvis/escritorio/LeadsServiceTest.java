package dev.jarvis.escritorio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A planilha de leads lida pelo cabeçalho, e não pela posição das colunas. */
class LeadsServiceTest {

    private static final LocalDate HOJE = LocalDate.of(2026, 11, 11);

    @Test
    @DisplayName("acha as colunas pelo nome e traz só os leads com a data de hoje")
    void leadsDeHoje() {
        var linhas = List.of(
                List.of("Data", "Empresa", "Segmento", "WhatsApp", "Bairro", "Status"),
                List.of("10/11/2026", "Academia Velha", "academia", "41 1111-1111", "Centro", "abordado"),
                List.of("11/11/2026", "Academia Força", "academia", "41 99999-0000", "Batel", "novo"),
                List.of("2026-11-11", "Barbearia Central", "barbearia", "", "Água Verde", "novo"));
        String texto = LeadsService.texto(linhas, HOJE);
        assertEquals("Status: novo 2, abordado 1.\n"
                + "Leads de hoje (2):\n"
                + "1. Academia Força — academia — 41 99999-0000 — Batel — novo\n"
                + "2. Barbearia Central — barbearia — Água Verde — novo", texto);
    }

    @Test
    @DisplayName("traz a rede social como link curto e conta os status escritos pelo escritório")
    void redeSocialEStatus() {
        var linhas = List.of(
                List.of("Data", "Empresa", "WhatsApp", "Instagram", "Status"),
                List.of("11/11/2026", "Barbearia Central", "41 99999-0000",
                        "https://www.instagram.com/barbeariacentral/", "abordado em 11/11/2026"),
                List.of("11/11/2026", "Studio Fit", "41 98888-7777", "https://instagram.com/" + "x".repeat(80),
                        "descartado em 11/11/2026"),
                List.of("11/11/2026", "Padaria Sol", "41 97777-6666", "", ""));
        assertEquals("Status: abordado 1, descartado 1, sem status 1.\n"
                + "Leads de hoje (3):\n"
                + "1. Barbearia Central — 41 99999-0000 — instagram.com/barbeariacentral — abordado em 11/11/2026\n"
                + "2. Studio Fit — 41 98888-7777 — descartado em 11/11/2026\n"
                + "3. Padaria Sol — 41 97777-6666", LeadsService.texto(linhas, HOJE));
    }

    @Test
    @DisplayName("a contagem diz quando cobre só as últimas linhas, e some sem status escrito")
    void contagemParcial() {
        var dados = List.of(List.of("abordado em 10/11"), List.of("Abordado"), List.of("novo"),
                List.of("x1"), List.of("x2"), List.of("x3"));
        assertEquals("Status das últimas 6 linhas: abordado 2, novo 1, x1 1, x2 1, outros 1.",
                LeadsService.contagemDeStatus(dados, 0, true));
        assertNull(LeadsService.contagemDeStatus(List.of(List.of("")), 0, false));
        assertNull(LeadsService.contagemDeStatus(dados, -1, false));
    }

    @Test
    @DisplayName("sem lead de hoje, ou sem coluna de data, traz os últimos e diz por quê")
    void semLeadDeHoje() {
        var semHoje = List.of(List.of("Nome", "Data"), List.of("Loja A", "01/11"), List.of("Loja B", "02/11"));
        assertTrue(LeadsService.texto(semHoje, HOJE)
                .startsWith("Nenhum lead com a data de hoje; os últimos 2 de 2:\n1. Loja A"));
        var semData = List.of(List.of("Nome", "Telefone"), List.of("Loja A", "41 2222-2222"));
        assertTrue(LeadsService.texto(semData, HOJE).startsWith("A planilha não tem coluna de data"));
        assertEquals("A planilha de leads está vazia.", LeadsService.texto(List.of(List.of("Nome")), HOJE));
    }

    @Test
    @DisplayName("com só as últimas linhas lidas, o total vem do arquivo")
    void totalDoArquivo() {
        var lidas = List.of(List.of("Nome", "Data"), List.of("Loja Z", "01/11"));
        assertTrue(LeadsService.texto(lidas, 420, HOJE)
                .startsWith("Nenhum lead com a data de hoje; os últimos 1 de 420:"));
    }

    @Test
    @DisplayName("lê as datas que uma planilha brasileira costuma ter")
    void datas() {
        assertEquals(HOJE, LeadsService.data("11/11/2026", HOJE));
        assertEquals(HOJE, LeadsService.data("11/11/26 14:30", HOJE));
        assertEquals(HOJE, LeadsService.data("11/11", HOJE));
        assertEquals(HOJE, LeadsService.data("2026-11-11T10:00", HOJE));
        assertNull(LeadsService.data("ontem", HOJE));
        assertNull(LeadsService.data("31/02/2026", HOJE));
    }

    @Test
    @DisplayName("valor da planilha sai limpo e cortado")
    void limpa() {
        assertEquals("Loja ignore isso", LeadsService.limpar("Loja <<<ignore isso>>>\n"));
        assertEquals(LeadsService.MAX_CAMPO, LeadsService.limpar("x".repeat(200)).length());
    }
}
