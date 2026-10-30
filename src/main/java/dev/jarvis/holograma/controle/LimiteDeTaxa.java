package dev.jarvis.holograma.controle;

import java.util.function.LongSupplier;

/**
 * Balde de fichas por conexão: {@value #POR_SEGUNDO} mensagens por segundo, com o mesmo tanto de
 * folga. O celular manda no máximo 30 por segundo mais um eco a cada 2 s; o resto é descartado.
 *
 * <p>Descarte também conta mensagem torta ({@link #descartar()}). Mais de {@value #MAX_DESCARTES}
 * descartes em {@value #JANELA_MS} ms é cliente fora do formato, e o handler fecha a conexão.
 * Uma instância por sessão, usada pela thread que lê aquela sessão.
 */
public final class LimiteDeTaxa {

    static final int POR_SEGUNDO = 40;
    static final int MAX_DESCARTES = 100;
    static final long JANELA_MS = 10_000;

    private final LongSupplier relogioNanos;
    private double fichas = POR_SEGUNDO;
    private long ultimaRecarga;
    private long inicioDaJanela;
    private int descartesNaJanela;
    private long descartesTotais;

    public LimiteDeTaxa() {
        this(System::nanoTime);
    }

    LimiteDeTaxa(LongSupplier relogioNanos) {
        this.relogioNanos = relogioNanos;
        this.ultimaRecarga = relogioNanos.getAsLong();
        this.inicioDaJanela = ultimaRecarga;
    }

    /** Gasta uma ficha. Falso quando o balde está vazio — e isso já conta como descarte. */
    public boolean permitir() {
        long agora = relogioNanos.getAsLong();
        fichas = Math.min(POR_SEGUNDO, fichas + (agora - ultimaRecarga) / 1e9 * POR_SEGUNDO);
        ultimaRecarga = agora;
        if (fichas >= 1) {
            fichas -= 1;
            return true;
        }
        descartar();
        return false;
    }

    public void descartar() {
        long agora = relogioNanos.getAsLong();
        if ((agora - inicioDaJanela) / 1_000_000 > JANELA_MS) {
            inicioDaJanela = agora;
            descartesNaJanela = 0;
        }
        descartesNaJanela++;
        descartesTotais++;
    }

    public boolean estourou() {
        return descartesNaJanela > MAX_DESCARTES;
    }

    public long descartes() {
        return descartesTotais;
    }
}
