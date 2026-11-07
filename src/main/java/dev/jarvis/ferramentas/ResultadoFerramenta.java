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
        List<ComandoDoHolograma> holograma, boolean encerrarTurno, boolean falhou) {
    /**
     * @param falhou a ação tentou e não conseguiu — marcado pela ferramenta, onde o resultado
     *               estruturado (o {@code ok} do agente) é conhecido, nunca farejado da prosa. Não é
     *               {@code is_error}: o modelo recebe o texto de sempre. Recusa da política, pergunta
     *               aberta e erro de validação de entrada não são falha.
     */
    public ResultadoFerramenta(String texto, List<Artefato> artefatos,
            List<Referencia> referencias, Confirmacao confirmacao,
            List<CardTela> cards, List<ComandoTela> comandos, EscutaResposta escuta,
            List<ComandoDoHolograma> holograma, boolean encerrarTurno) {
        this(texto, artefatos, referencias, confirmacao, cards, comandos, escuta, holograma, encerrarTurno, false);
    }
    /** A ação tentou e não conseguiu (ver {@code falhou}). */
    public static ResultadoFerramenta falha(String texto) {
        return texto(texto).comoFalha();
    }
    /** O mesmo resultado, marcado como falha. */
    public ResultadoFerramenta comoFalha() {
        return new ResultadoFerramenta(texto, artefatos, referencias, confirmacao, cards, comandos,
                escuta, holograma, encerrarTurno, true);
    }
    public ResultadoFerramenta(String texto, List<Artefato> artefatos,
            List<Referencia> referencias, Confirmacao confirmacao,
            List<CardTela> cards, List<ComandoTela> comandos, EscutaResposta escuta,
            List<ComandoDoHolograma> holograma) {
        this(texto, artefatos, referencias, confirmacao, cards, comandos, escuta, holograma, false);
    }
    public static ResultadoFerramenta imediata(String texto, Confirmacao confirmacao) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), confirmacao,
                List.of(), List.of(), null, List.of(), true);
    }
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
    /**
     * "O PC está offline, nada foi iniciado." Na conversa é só o texto; numa tarefa de fundo vira
     * espera pelo PC, e a ferramenta roda de novo quando ele voltar — sem passo com falha.
     */
    public static ResultadoFerramenta pcOffline(String texto) {
        dev.jarvis.tarefas.ContextoDaTarefa.esperarPcSeEmTarefa();
        return texto(texto);
    }
    public static ResultadoFerramenta texto(String texto) {
        return new ResultadoFerramenta(texto, List.of(), List.of(), null, List.of(), List.of());
    }
    /**
     * O que a {@code PoliticaDeAutonomia} decidiu, no formato de ferramenta: com a confirmação em
     * aberto quando ela pergunta, só o texto quando fez ou recusou.
     */
    public static ResultadoFerramenta de(dev.jarvis.aprovacao.Aprovacoes.Encaminhamento encaminhamento) {
        return new ResultadoFerramenta(encaminhamento.texto(), List.of(), List.of(),
                encaminhamento.confirmacao(), List.of(), List.of());
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
