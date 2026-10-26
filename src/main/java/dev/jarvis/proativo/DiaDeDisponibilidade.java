package dev.jarvis.proativo;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O acumulador de um dia: por site, quantas checagens, quantas falharam e quantas quedas houve.
 *
 * <p><b>Agregado, e não uma linha por checagem</b> — é a diferença que separa este arquivo do
 * {@code DiaDoDiario}. Um site checado a cada cinco minutos dá 288 checagens por dia; três sites
 * por trinta dias dariam 26 mil linhas por mês para responder a uma pergunta que é <i>um número</i>
 * ("quanto o site ficou no ar em outubro?"). O {@code CustoDiarioEmArquivo} agrega pela mesma
 * razão, e o diário de ações <b>não</b> agrega pela razão oposta: "o que você fez hoje?" quer os
 * fatos, um por um.
 *
 * <p>Por isso também não há teto de linhas aqui: o tamanho do dia não depende de quantas vezes algo
 * aconteceu, só de quantos sites existem.
 */
class DiaDeDisponibilidade {

    /** O que se sabe de um site num dia. Mutável de propósito: é um acumulador. */
    static final class Contagem {
        private int checagens;
        private int falhas;
        private int quedas;

        Contagem() {
        }

        Contagem(int checagens, int falhas, int quedas) {
            this.checagens = Math.max(0, checagens);
            this.falhas = Math.max(0, falhas);
            this.quedas = Math.max(0, quedas);
        }

        int checagens() { return checagens; }
        int falhas() { return falhas; }
        int quedas() { return quedas; }

        void somarChecagem(boolean ok) {
            checagens++;
            if (!ok) {
                falhas++;
            }
        }

        void somarQueda() {
            quedas++;
        }

        Contagem copia() {
            return new Contagem(checagens, falhas, quedas);
        }
    }

    private final LocalDate dia;
    private final Map<String, Contagem> porSite = new LinkedHashMap<>();

    DiaDeDisponibilidade(LocalDate dia) {
        this.dia = dia;
    }

    LocalDate dia() {
        return dia;
    }

    boolean vazio() {
        return porSite.isEmpty();
    }

    Map<String, Contagem> porSite() {
        return porSite;
    }

    void somarChecagem(String site, boolean ok) {
        porSite.computeIfAbsent(site, nome -> new Contagem()).somarChecagem(ok);
    }

    void somarQueda(String site) {
        porSite.computeIfAbsent(site, nome -> new Contagem()).somarQueda();
    }

    /** Usado para gravar fora da trava o retrato de um dia que continua recebendo checagens. */
    DiaDeDisponibilidade copia() {
        DiaDeDisponibilidade copia = new DiaDeDisponibilidade(dia);
        porSite.forEach((site, contagem) -> copia.porSite.put(site, contagem.copia()));
        return copia;
    }
}
