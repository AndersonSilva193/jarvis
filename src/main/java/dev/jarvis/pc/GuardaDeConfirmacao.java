package dev.jarvis.pc;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.jarvis.util.ComparadorDeToken;

/**
 * O código de 4 dígitos que separa um pedido de desligar o PC do desligamento de verdade.
 *
 * O fluxo tem dois passos: a primeira chamada de {@code controlar_pc} emite um código, e só uma
 * segunda chamada com o mesmo código executa. O código volta ao modelo, então isto é proteção
 * contra <b>acidente</b> (um "desliga" entendido no meio de outra frase), não contra o modelo
 * decidir se autoconfirmar. O que torna o espaço de 10.000 suficiente são quatro travas:
 *
 * <ol>
 *   <li><b>uso único</b> — o {@code getAndSet(null)} consome a pendência na primeira conferência;</li>
 *   <li><b>tentativa errada queima a pendência</b> — não há segunda chance, nem dentro de um turno
 *       (o {@code Assistente} dá 8 passos) nem ao longo de vários, então não há força bruta;</li>
 *   <li><b>dois minutos</b> de validade;</li>
 *   <li><b>amarrado a ação e argumento</b> — código emitido para desligar jamais reinicia.</li>
 * </ol>
 *
 * Mais o sorteio com {@link SecureRandom}: o código é a única coisa entre um modelo confuso — ou
 * uma injeção de prompt vinda de uma busca na web ou de um {@code og:title} do {@code abrir_pagina}
 * — e um {@code shutdown} real na máquina do usuário.
 *
 * <p>Componente próprio, e não um campo do {@code PcService} (que é transporte até o agente) nem
 * da ferramenta: assim o {@code Clock} entra pelo construtor e o teste de expiração é relógio
 * fixo em vez de dois minutos de {@code sleep}.
 */
@Component
public class GuardaDeConfirmacao {

    private static final Logger log = LoggerFactory.getLogger(GuardaDeConfirmacao.class);

    static final Duration VALIDADE = Duration.ofMinutes(2);

    /** O veredito de uma conferência. Só {@code CONFIRMADA} autoriza a execução. */
    public enum Veredito {
        CONFIRMADA, INCORRETA, EXPIRADA, INEXISTENTE
    }

    private record Pendencia(String acao, String argumento, String codigo, Instant expira) {
    }

    /**
     * Uma só confirmação em aberto. É um PC e um usuário; duas pendências tornariam o código
     * ambíguo ("4821 era do desligar ou do reiniciar?").
     */
    private final AtomicReference<Pendencia> pendente = new AtomicReference<>();
    private final SecureRandom sorteio = new SecureRandom();
    private final Clock relogio;

    public GuardaDeConfirmacao() {
        this(Clock.systemUTC());
    }

    public GuardaDeConfirmacao(Clock relogio) {
        this.relogio = relogio;
    }

    /**
     * Emite o código de uma ação perigosa, invalidando o que estivesse em aberto.
     *
     * <p>Pedir {@code reiniciar} logo depois de {@code desligar} descarta o código do desligar, e
     * esse é o lado seguro da corrida: o pior resultado possível é o usuário ter de pedir de novo,
     * nunca uma ação executada com o código de outra.
     */
    public String pedir(String acao, String argumento) {
        String codigo = String.format("%04d", sorteio.nextInt(10_000));
        Pendencia anterior = pendente.getAndSet(new Pendencia(acao, normalizar(argumento), codigo,
                relogio.instant().plus(VALIDADE)));
        if (anterior != null) {
            log.info("Confirmação anterior de '{}' descartada por um pedido novo", anterior.acao());
        }
        log.info("Confirmação pedida para '{}' no PC (vale {} s)", acao, VALIDADE.toSeconds());
        return codigo;
    }

    /**
     * Consome a confirmação e julga o código.
     *
     * <p>A pendência é retirada <b>antes</b> do julgamento, de propósito: qualquer veredito
     * diferente de {@code CONFIRMADA} a queima, e é isso que fecha a porta para tentativa
     * repetida.
     */
    public Veredito conferir(String acao, String argumento, String codigo) {
        Pendencia pendencia = pendente.getAndSet(null);
        if (pendencia == null) {
            return Veredito.INEXISTENTE;
        }
        if (!relogio.instant().isBefore(pendencia.expira())) {
            return Veredito.EXPIRADA;
        }
        boolean casa = pendencia.acao().equals(acao)
                && pendencia.argumento().equals(normalizar(argumento))
                && ComparadorDeToken.igual(codigo, pendencia.codigo());
        if (!casa) {
            log.warn("Código de confirmação incorreto para '{}'; confirmação cancelada", acao);
        }
        return casa ? Veredito.CONFIRMADA : Veredito.INCORRETA;
    }

    /** Esquece a confirmação em aberto. Cancelou o desligamento, cancelou a pendência. */
    public void cancelar() {
        pendente.set(null);
    }

    private static String normalizar(String argumento) {
        return argumento == null ? "" : argumento.trim();
    }
}
