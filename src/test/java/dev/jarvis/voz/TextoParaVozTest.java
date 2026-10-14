package dev.jarvis.voz;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Marcação que sobra vira som estranho e crédito gasto à toa. */
class TextoParaVozTest {

    @Test
    @DisplayName("tira negrito, itálico e crase mantendo o texto")
    void tiraEnfase() {
        assertEquals("O servidor está no ar.", TextoParaVoz.limpar("O servidor está **no ar**."));
        assertEquals("O servidor está no ar.", TextoParaVoz.limpar("O servidor está _no ar_."));
        assertEquals("Rodei o comando docker ps.", TextoParaVoz.limpar("Rodei o comando `docker ps`."));
    }

    @Test
    @DisplayName("do link fica o rótulo, não a URL")
    void tiraLink() {
        assertEquals("Olhe a matéria da Folha.",
                TextoParaVoz.limpar("Olhe a [matéria da Folha](https://folha.com.br/x?y=1)."));
    }

    @Test
    @DisplayName("título e marcador de lista somem do começo da linha")
    void tiraTituloELista() {
        assertEquals("Clima\nSol de manhã.\nChuva à tarde.",
                TextoParaVoz.limpar("## Clima\n- Sol de manhã.\n* Chuva à tarde."));
    }

    @Test
    @DisplayName("sublinhado no meio de um nome não é itálico")
    void preservaNomeComSublinhado() {
        assertEquals("A variável HUD_TOKEN está vazia.",
                TextoParaVoz.limpar("A variável HUD_TOKEN está vazia."));
    }

    @Test
    @DisplayName("texto vazio ou nulo não vira fala nenhuma")
    void vazio() {
        assertEquals("", TextoParaVoz.limpar(null));
        assertEquals("", TextoParaVoz.limpar("   "));
    }
}
