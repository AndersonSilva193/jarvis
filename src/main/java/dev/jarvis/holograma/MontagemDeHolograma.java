package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.jarvis.util.Json;

/**
 * Um objeto sendo lido, linha a linha: junta o {@link HologramaLineParser} e o
 * {@link HologramaValidator} e decide a ordem do que sai.
 *
 * <p>Três garantias para quem transmite, valha o que o modelo escrever: a {@code meta} sai uma
 * vez e antes de qualquer peça (a padrão, com aviso, se o modelo começou direto pelas peças);
 * nada sai depois do {@code fim}; e o {@code fim} sai sempre — {@link SaidaHolograma#COMPLETO}
 * quando o modelo escreveu a linha de fim, {@link SaidaHolograma#INCOMPLETO} com o motivo quando
 * quem fechou foi o servidor (teto de tokens, prazo, falha).
 *
 * <p><b>Continuação.</b> Um objeto cortado pode receber mais linhas de uma segunda chamada ao
 * modelo: {@link #continuar()} reabre a montagem e, dali em diante, a meta repetida é ignorada
 * calada e a peça com id que já existe é <b>descartada</b>, não renomeada — o modelo que recomeça
 * do início desenharia o objeto de novo por cima do que já está na tela. As
 * {@link #linhasAceitas()} são o que se manda de volta ao modelo para ele saber onde parou.
 *
 * <p>Uma instância por objeto, como o validador que ela carrega.
 */
public final class MontagemDeHolograma {

    static final String MOTIVO_SEM_FIM = "a resposta acabou sem a linha de fim";
    /** Quantas linhas descartadas guardar em texto, e até quantos caracteres cada: é para o log. */
    private static final int MAX_DESCARTADAS_GUARDADAS = 5;
    private static final int MAX_TEXTO_DESCARTADO = 300;

    private final HologramaLineParser parser;
    private final HologramaValidator validador = new HologramaValidator();
    private boolean temMeta;
    private boolean terminou;
    private boolean continuando;
    private SaidaHolograma.Fim fim;
    private int avisos;
    private int descartadas;
    private int repetidas;
    private final List<String> textos = new ArrayList<>();
    private final List<String> aceitas = new ArrayList<>();
    private final List<String> descartadasBrutas = new ArrayList<>();

    public MontagemDeHolograma(HologramaLineParser parser) {
        this.parser = parser;
    }

    public List<SaidaHolograma> processar(String linha) {
        if (terminou) {
            return List.of();
        }
        Optional<EventoHolograma> evento = parser.ler(linha);
        if (evento.isEmpty()) {
            if (linha != null && !linha.isBlank()) {
                descartadas++;
                if (descartadasBrutas.size() < MAX_DESCARTADAS_GUARDADAS) {
                    String bruta = linha.strip();
                    descartadasBrutas.add(bruta.length() > MAX_TEXTO_DESCARTADO
                            ? bruta.substring(0, MAX_TEXTO_DESCARTADO) + "…" : bruta);
                }
            }
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        switch (evento.get()) {
            case EventoHolograma.Meta m -> {
                if (!temMeta) {
                    temMeta = true;
                    aceitas.add(linha.strip());
                    saidas.add(new SaidaHolograma.Meta(m.meta()));
                } else if (!continuando) {
                    aviso(saidas, "Meta repetida; ficou a primeira.");
                }
            }
            case EventoHolograma.Peca p -> {
                if (continuando && validador.usado(Json.texto(p.compacta().get("id")))) {
                    repetidas++;
                    return List.of();
                }
                garantirMeta(saidas);
                HologramaValidator.Validacao validacao = validador.validar(p.compacta());
                if (!validacao.pecas().isEmpty()) {
                    aceitas.add(linha.strip());
                }
                validacao.pecas().forEach(peca -> saidas.add(new SaidaHolograma.Peca(peca)));
                validacao.avisos().forEach(texto -> aviso(saidas, texto));
            }
            case EventoHolograma.Fim f -> fechar(saidas);
        }
        return saidas;
    }

    /** Fecha o objeto se o {@code fim} não veio, com aviso e como incompleto. Fechado, não devolve nada. */
    public List<SaidaHolograma> terminar() {
        if (terminou) {
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        aviso(saidas, "A resposta acabou sem a linha de fim.");
        return incompleto(saidas, MOTIVO_SEM_FIM);
    }

    /**
     * Fecha como incompleto, sem aviso: quem chama já sabe o motivo e o diz no {@code fim}. É o
     * caso do objeto que, mesmo depois das continuações, não chegou à linha de fim.
     */
    public List<SaidaHolograma> incompleto(String motivo) {
        if (terminou) {
            return List.of();
        }
        return incompleto(new ArrayList<>(), motivo);
    }

    private List<SaidaHolograma> incompleto(List<SaidaHolograma> saidas, String motivo) {
        garantirMeta(saidas);
        terminou = true;
        fim = SaidaHolograma.Fim.incompleto(validador.total(), avisos, motivo);
        saidas.add(fim);
        return saidas;
    }

    /**
     * Fecha o objeto porque a geração parou antes da hora: um {@link SaidaHolograma.Erro} com o
     * texto e o {@code fim} incompleto, com o mesmo texto de motivo. Depois de fechado não devolve
     * nada. Sem meta ainda, sai a padrão — calada, porque o erro já explica o que faltou.
     */
    public List<SaidaHolograma> interromper(String texto) {
        if (terminou) {
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        if (!temMeta) {
            temMeta = true;
            saidas.add(new SaidaHolograma.Meta(HologramaMeta.PADRAO));
        }
        saidas.add(new SaidaHolograma.Erro(texto));
        terminou = true;
        fim = SaidaHolograma.Fim.incompleto(validador.total(), avisos, texto);
        saidas.add(fim);
        return saidas;
    }

    /**
     * Fecha sem reclamar da linha de fim que faltou: é o caso de quem parou o modelo de propósito,
     * no limite de peças — o validador já avisou do que ficou de fora, e não há o que continuar.
     */
    public List<SaidaHolograma> encerrar() {
        if (terminou) {
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        fechar(saidas);
        return saidas;
    }

    /**
     * Reabre para receber a continuação: um objeto fechado como incompleto volta a aceitar linhas.
     * Completo não reabre — não há o que acrescentar a ele.
     */
    public void continuar() {
        continuando = true;
        if (terminou && !completo()) {
            terminou = false;
            fim = null;
        }
    }

    public boolean terminou() {
        return terminou;
    }

    /** Fechou com o {@code fim} do modelo (ou no limite de peças), e não pelo servidor. */
    public boolean completo() {
        return terminou && fim != null && fim.completo();
    }

    /** O último {@code fim} que saiu, para repetir a quem reconecta; vazio enquanto aberta. */
    public Optional<SaidaHolograma.Fim> fim() {
        return Optional.ofNullable(fim);
    }

    /** Chegou ao {@link HologramaValidator#MAX_PECAS}: o que o modelo escrever daqui em diante é descartado. */
    public boolean cheia() {
        return validador.total() >= HologramaValidator.MAX_PECAS;
    }

    /** Quantas peças já saíram, contando as cópias. */
    public int pecas() {
        return validador.total();
    }

    public int avisos() {
        return avisos;
    }

    /** Linhas com texto que o parser não aceitou: cerca de markdown, prosa, JSON quebrado ou cortado. */
    public int descartadas() {
        return descartadas;
    }

    /** As primeiras linhas descartadas, em texto (cortadas): é o que o log mostra. */
    public List<String> descartadasBrutas() {
        return List.copyOf(descartadasBrutas);
    }

    /** Peças da continuação com id que já existia, descartadas caladas. */
    public int repetidas() {
        return repetidas;
    }

    /** As linhas que viraram meta ou peça, como o modelo as escreveu: a base da continuação. */
    public List<String> linhasAceitas() {
        return List.copyOf(aceitas);
    }

    private void garantirMeta(List<SaidaHolograma> saidas) {
        if (!temMeta) {
            temMeta = true;
            saidas.add(new SaidaHolograma.Meta(HologramaMeta.PADRAO));
            aviso(saidas, "Objeto sem linha de meta; usei o nome padrão.");
        }
    }

    private void fechar(List<SaidaHolograma> saidas) {
        garantirMeta(saidas);
        terminou = true;
        fim = new SaidaHolograma.Fim(validador.total(), avisos);
        saidas.add(fim);
    }

    /** O texto dos avisos que já saíram, na ordem: é o que o log mostra para explicar o contador. */
    public List<String> textosDeAviso() {
        return List.copyOf(textos);
    }

    private void aviso(List<SaidaHolograma> saidas, String texto) {
        avisos++;
        if (textos.size() < 20) {
            textos.add(texto);
        }
        saidas.add(new SaidaHolograma.Aviso(texto));
    }
}
