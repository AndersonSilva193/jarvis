package dev.jarvis.ferramentas;

import java.nio.file.Path;
import java.util.List;
import dev.jarvis.conversa.Confirmacao;
import dev.jarvis.conversa.EscutaResposta;
import dev.jarvis.holograma.ComandoDoHolograma;
import dev.jarvis.tela.CardTela;
import dev.jarvis.tela.ComandoTela;

/**
 * Metadados emitidos pelo código da ferramenta, nunca extraídos de caminhos escritos pelo modelo.
 *
 * @param cards    janelas para a HUD abrir. Quem publica o evento é o registro {@link Ferramentas},
 *                 logo depois da execução, para o card aparecer na tela enquanto o modelo ainda
 *                 está montando a fala — e não no fim do turno, como acontece com os anexos.
 * @param comandos ordens sobre o que já está aberto na tela.
 * @param holograma ordens para a cena do holograma ({@code ComandoDoHolograma}), na ordem: o
 *                  registro as publica como {@code holograma.comando}, pelo mesmo caminho dos comandos.
 */
public record ResultadoFerramenta(String texto, List<Artefato> artefatos,
        List<Referencia> referencias, Confirmacao confirmacao,
        List<CardTela> cards, List<ComandoTela> comandos, EscutaResposta escuta,
        List<ComandoDoHolograma> holograma) {
    public ResultadoFerramenta(String texto, List<Artefato> artefatos,
            List<Referencia> referencias, Confirmacao confirmacao,
            List<CardTela> cards, List<ComandoTela> comandos, EscutaResposta escuta) {
        this(texto, artefatos, referencias, confirmacao, cards, comandos, escuta, null);
    }
    public ResultadoFerramenta(String texto, List<Artefato> artefatos,
            List<Referencia> referencias, Confirmacao confirmacao,
            List<CardTela> cards, List<ComandoTela> comandos) {
        this(texto, artefatos, referencias, confirmacao, cards, comandos, null);
    }
    public record Artefato(Path arquivo, String nome, String mime) { }
    public record Referencia(String nome, String url) { }
    public ResultadoFerramenta {
        artefatos = artefatos == null ? List.of() : List.copyOf(artefatos);
        referencias = referencias == null ? List.of() : List.copyOf(referencias);
        cards = cards == null ? List.of() : List.copyOf(cards);
        comandos = comandos == null ? List.of() : List.copyOf(comandos);
        holograma = holograma == null ? List.of() : List.copyOf(holograma);
    }
    public static ResultadoFerramenta texto(String texto) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null, List.of(), List.of());
    }
    /** Resultado que abre uma janela na tela, mais o texto curto que volta ao modelo. */
    public static ResultadoFerramenta comCard(String texto, CardTela card) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null, List.of(card), List.of());
    }
    /** Resultado que comanda o que já está na tela. */
    public static ResultadoFerramenta comComando(String texto, ComandoTela comando) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null, List.of(), List.of(comando));
    }
    /** Resultado que mexe na cena do holograma. */
    public static ResultadoFerramenta comHolograma(String texto, ComandoDoHolograma comando) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null, List.of(), List.of(), null,
                List.of(comando));
    }
}
