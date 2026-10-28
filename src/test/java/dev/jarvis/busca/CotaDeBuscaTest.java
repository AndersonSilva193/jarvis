package dev.jarvis.busca;

import static dev.jarvis.busca.HttpDublado.props;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.jarvis.custo.MedidorDeCusto;
import dev.jarvis.pc.RelogioFalso;
import dev.jarvis.proativo.AvisosProativos;
import dev.jarvis.proativo.FalasService;

/** O aviso sai ao cruzar 80% e 100%, uma vez cada — e em 100% o Tavily sai da vez. */
class CotaDeBuscaTest {

    private final MedidorDeCusto medidor = mock(MedidorDeCusto.class);
    private final AvisosProativos avisos = mock(AvisosProativos.class);

    private CotaDeBusca cota(int mensal) {
        return new CotaDeBusca(medidor, avisos, new FalasService(), props("tavily", "tvly-x", "", mensal),
                new RelogioFalso("2026-10-28T15:00:00Z"));
    }

    @Test
    @DisplayName("cruzando 80%, avisa uma vez com o número do mês")
    void oitenta() {
        when(medidor.buscasDoMes("tavily")).thenReturn(799L);
        cota(1000).registrar("tavily");

        verify(medidor).registrarBusca("tavily");
        ArgumentCaptor<String> texto = ArgumentCaptor.forClass(String.class);
        verify(avisos).avisar(eq("busca:tavily"), eq("alta:2026-10"), texto.capture(), eq(null));
        assertEquals("A cota de buscas do Tavily está em 80% este mês (800 de 1000).", texto.getValue());
    }

    @Test
    @DisplayName("acima de 80% sem cruzar nada, não avisa de novo")
    void semCruzamento() {
        when(medidor.buscasDoMes("tavily")).thenReturn(850L);
        cota(1000).registrar("tavily");
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("chegando a 100%, avisa que acabou e o Tavily passa a estar esgotado")
    void cem() {
        when(medidor.buscasDoMes("tavily")).thenReturn(999L);
        CotaDeBusca cota = cota(1000);
        assertFalse(cota.esgotada("tavily"));
        cota.registrar("tavily");
        verify(avisos).avisar(eq("busca:tavily"), eq("esgotada:2026-10"), anyString(), eq(null));

        when(medidor.buscasDoMes("tavily")).thenReturn(1000L);
        assertTrue(cota.esgotada("tavily"));
        assertFalse(cota.esgotada("searxng"), "só o Tavily tem cota");
    }

    @Test
    @DisplayName("salto que passa dos dois limiares manda só o de 100%")
    void salto() {
        when(medidor.buscasDoMes("tavily")).thenReturn(0L);
        cota(1).registrar("tavily");
        verify(avisos).avisar(eq("busca:tavily"), eq("esgotada:2026-10"), anyString(), eq(null));
        verify(avisos, never()).avisar(anyString(), eq("alta:2026-10"), anyString(), any());
    }

    @Test
    @DisplayName("cota zero desliga a conta: nunca esgota, nunca avisa, mas a busca é contada")
    void semCota() {
        when(medidor.buscasDoMes("tavily")).thenReturn(5000L);
        CotaDeBusca cota = cota(0);
        assertFalse(cota.esgotada("tavily"));
        cota.registrar("tavily");
        verify(medidor).registrarBusca("tavily");
        verify(avisos, never()).avisar(anyString(), anyString(), anyString(), any());
    }
}
