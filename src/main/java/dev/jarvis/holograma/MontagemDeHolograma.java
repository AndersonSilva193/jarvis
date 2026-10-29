package dev.jarvis.holograma;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Um objeto sendo lido, linha a linha: junta o {@link HologramaLineParser} e o
 * {@link HologramaValidator} e decide a ordem do que sai.
 *
 * <p>Três garantias para quem transmite, valha o que o modelo escrever: a {@code meta} sai uma
 * vez e antes de qualquer peça (a padrão, com aviso, se o modelo começou direto pelas peças);
 * nada sai depois do {@code fim}; e o {@code fim} sai sempre — pelo {@link #terminar()}, quando a
 * resposta acabou sem ele, que é o que acontece quando o modelo bate no teto de tokens.
 *
 * <p>Uma instância por objeto, como o validador que ela carrega.
 */
public final class MontagemDeHolograma {

    private final HologramaLineParser parser;
    private final HologramaValidator validador = new HologramaValidator();
    private boolean temMeta;
    private boolean terminou;
    private int avisos;
    private int descartadas;
    private final List<String> textos = new ArrayList<>();

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
            }
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        switch (evento.get()) {
            case EventoHolograma.Meta m -> {
                if (temMeta) {
                    aviso(saidas, "Meta repetida; ficou a primeira.");
                } else {
                    temMeta = true;
                    saidas.add(new SaidaHolograma.Meta(m.meta()));
                }
            }
            case EventoHolograma.Peca p -> {
                garantirMeta(saidas);
                HologramaValidator.Validacao validacao = validador.validar(p.compacta());
                validacao.pecas().forEach(peca -> saidas.add(new SaidaHolograma.Peca(peca)));
                validacao.avisos().forEach(texto -> aviso(saidas, texto));
            }
            case EventoHolograma.Fim f -> fechar(saidas);
        }
        return saidas;
    }

    /** Fecha o objeto se o {@code fim} não veio; depois de fechado, não devolve nada. */
    public List<SaidaHolograma> terminar() {
        if (terminou) {
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        aviso(saidas, "A resposta acabou sem a linha de fim.");
        fechar(saidas);
        return saidas;
    }

    /**
     * Fecha o objeto porque a geração parou antes da hora: um {@link SaidaHolograma.Erro} com o
     * texto e o {@code fim}. Depois de fechado não devolve nada. Sem meta ainda, sai a padrão —
     * calada, porque o erro já explica o que faltou.
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
        saidas.add(new SaidaHolograma.Fim(validador.total(), avisos));
        return saidas;
    }

    /**
     * Fecha sem reclamar da linha de fim que faltou: é o caso de quem parou o modelo de propósito,
     * no limite de peças — o validador já avisou do que ficou de fora.
     */
    public List<SaidaHolograma> encerrar() {
        if (terminou) {
            return List.of();
        }
        List<SaidaHolograma> saidas = new ArrayList<>();
        fechar(saidas);
        return saidas;
    }

    public boolean terminou() {
        return terminou;
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

    /** Linhas com texto que o parser não aceitou: cerca de markdown, prosa, JSON quebrado. */
    public int descartadas() {
        return descartadas;
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
        saidas.add(new SaidaHolograma.Fim(validador.total(), avisos));
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
