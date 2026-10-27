package dev.jarvis.custo;

/**
 * Os números de um balde do dia — de uma origem ou de um modelo.
 *
 * Classe mutável, e não record, de propósito: ela é o acumulador vivo, somado a cada chamada ao
 * modelo. Um record aqui faria uma alocação por chamada só para trocar seis campos de lugar.
 */
class Uso {

    /**
     * Quantas respostas do modelo entraram neste balde.
     *
     * É o denominador da saída média por origem — o número que diz se uma troca de esforço
     * funcionou. Sem ele, "tokens de saída" sobe junto com o uso e não compara nada.
     */
    long chamadas;
    long entrada;
    long saida;
    long cacheEscrito;
    long cacheLido;
    long buscasWeb;
    double custoUsd;
    /** Houve consumo de um modelo fora de {@code CUSTO_PRECOS}: o custo deste balde é incompleto. */
    boolean semPreco;

    void somar(long chamadas, long entrada, long saida, long cacheEscrito, long cacheLido,
            long buscasWeb, double custoUsd, boolean semPreco) {
        this.chamadas += chamadas;
        this.entrada += entrada;
        this.saida += saida;
        this.cacheEscrito += cacheEscrito;
        this.cacheLido += cacheLido;
        this.buscasWeb += buscasWeb;
        this.custoUsd += custoUsd;
        this.semPreco |= semPreco;
    }

    Uso copia() {
        Uso copia = new Uso();
        copia.somar(chamadas, entrada, saida, cacheEscrito, cacheLido, buscasWeb, custoUsd, semPreco);
        return copia;
    }

    boolean vazio() {
        return entrada == 0 && saida == 0 && cacheEscrito == 0 && cacheLido == 0 && buscasWeb == 0;
    }
}
