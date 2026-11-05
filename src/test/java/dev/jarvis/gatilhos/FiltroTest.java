package dev.jarvis.gatilhos;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import dev.jarvis.lembretes.Recorrencia;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** O recorte da fonte: o que casa, o que não casa, e o que nem chega a virar regra. */
class FiltroTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> SITES = Set.of("loja", "blog");
    private static final Instant AGORA = Instant.parse("2026-11-02T12:00:00Z");

    private static JsonNode no(String json) {
        return JSON.readTree(json);
    }

    @Test void siteCasaSoComOMesmoNome() {
        Filtro f = Filtro.ler(FonteDeGatilho.SITE_CAIU, no("{\"site\":\"loja\"}"), SITES);
        assertTrue(f.casa(EventoDeGatilho.de(FonteDeGatilho.SITE_CAIU, AGORA, "site", "loja")));
        assertFalse(f.casa(EventoDeGatilho.de(FonteDeGatilho.SITE_CAIU, AGORA, "site", "blog")));
        assertEquals("o site loja cair", f.descreverEvento(FonteDeGatilho.SITE_CAIU));
    }

    @Test void semFiltroCasaComQualquerUm() {
        Filtro f = Filtro.ler(FonteDeGatilho.SITE_VOLTOU, null, SITES);
        assertTrue(f.casa(EventoDeGatilho.de(FonteDeGatilho.SITE_VOLTOU, AGORA, "site", "blog")));
        assertEquals("um site monitorado voltar", f.descreverEvento(FonteDeGatilho.SITE_VOLTOU));
    }

    @Test void arquivoPorFormatoETrechoDoNome() {
        Filtro f = Filtro.ler(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA,
                no("{\"formato\":\"XLSX\",\"nome\":\"Relatório\"}"), SITES);
        assertTrue(f.casa(EventoDeGatilho.de(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, AGORA,
                "nome", "relatorio_outubro.xlsx", "formato", "xlsx")));
        assertFalse(f.casa(EventoDeGatilho.de(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, AGORA,
                "nome", "relatorio_outubro.md", "formato", "md")), "formato diferente");
        assertFalse(f.casa(EventoDeGatilho.de(FonteDeGatilho.ARQUIVO_NOVO_NA_ENTRADA, AGORA,
                "nome", "notas.xlsx", "formato", "xlsx")), "nome sem o trecho");
    }

    @Test void cotaPorTipo() {
        Filtro f = Filtro.ler(FonteDeGatilho.COTA_VOZ, no("{\"tipo\":\"reserva\"}"), SITES);
        assertTrue(f.casa(EventoDeGatilho.de(FonteDeGatilho.COTA_VOZ, AGORA, "tipo", "reserva")));
        assertFalse(f.casa(EventoDeGatilho.de(FonteDeGatilho.COTA_VOZ, AGORA, "tipo", "antecipado")));
    }

    @Test void campoQueAFonteNaoEntendeERecusado() {
        var erro = assertThrows(IllegalArgumentException.class,
                () -> Filtro.ler(FonteDeGatilho.PC_LIGOU, no("{\"site\":\"loja\"}"), SITES));
        assertTrue(erro.getMessage().contains("não vale para pc_ligou"), erro.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> Filtro.ler(FonteDeGatilho.SITE_CAIU, no("{\"site\":\"inventado\"}"), SITES));
        assertThrows(IllegalArgumentException.class,
                () -> Filtro.ler(FonteDeGatilho.HORARIO, no("{}"), SITES), "horário sem quando");
    }

    @Test void horarioLeRecorrenciaComOsDias() {
        Filtro f = Filtro.ler(FonteDeGatilho.HORARIO,
                no("{\"quando\":\"2026-11-02T08:00\",\"recorrencia\":\"semanal\",\"dias_da_semana\":[\"segunda\",\"quarta\"]}"), SITES);
        assertEquals(Recorrencia.SEMANAL, f.repeticao().tipo());
        assertEquals(2, f.repeticao().diasDaSemana().size());
        assertEquals("for 08:00, toda segunda, quarta", f.descreverEvento(FonteDeGatilho.HORARIO));
    }
}
