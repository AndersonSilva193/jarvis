package dev.jarvis.pc;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import dev.jarvis.conversa.Confirmacao;

/** Autorizacao humana de uso unico, consumida antes de qualquer chamada ao modelo. */
@Component
public class IntencoesPc {
    public enum Cancelamento { CANCELADA, INEXISTENTE, RECUSADA }

    private record Pendente(String tipo, String descricao, String codigo, long usuario,
            String pedidoOrigem, Instant expira, Supplier<String> executar) { }

    private final SecureRandom sorteio = new SecureRandom();
    private final Clock relogio;
    private Pendente pendente;

    public IntencoesPc() { this(Clock.systemUTC()); }
    public IntencoesPc(Clock relogio) { this.relogio = relogio; }

    public synchronized Confirmacao pedir(String tipo, String descricao, long usuario,
            String pedidoOrigem, Supplier<String> executar) {
        return pedir(tipo, descricao, usuario, pedidoOrigem,
                relogio.instant().plus(Duration.ofMinutes(2)), executar);
    }

    public synchronized Confirmacao pedir(String tipo, String descricao, long usuario,
            String pedidoOrigem, Instant prazoMaximo, Supplier<String> executar) {
        if (usuario <= 0 || pedidoOrigem == null || pedidoOrigem.isBlank())
            throw new IllegalArgumentException("pedido autenticado necessario");
        if (pendente != null && relogio.instant().isBefore(pendente.expira())
                && (pendente.usuario() != usuario || pendente.pedidoOrigem().equals(pedidoOrigem)))
            throw new IllegalStateException("ja existe uma confirmacao do PC em aberto");
        String codigo = String.format("%06d", sorteio.nextInt(1_000_000));
        Instant expira = relogio.instant().plus(Duration.ofMinutes(2));
        if (prazoMaximo.isBefore(expira)) expira = prazoMaximo;
        if (!relogio.instant().isBefore(expira))
            throw new IllegalStateException("a proposta expirou; prepare uma nova edição");
        pendente = new Pendente(tipo, descricao, codigo, usuario, pedidoOrigem, expira, executar);
        return new Confirmacao(codigo, descricao, expira.toString());
    }

    /** Cancela somente a confirmacao de tarefa visual, sem tocar em tarefas ja iniciadas. */
    public synchronized Cancelamento cancelarTarefaPendente(long usuario, String pedidoAtual) {
        Pendente p = pendente;
        if (p == null || !p.tipo().equals("computer")) return Cancelamento.INEXISTENTE;
        if (!relogio.instant().isBefore(p.expira())) {
            pendente = null;
            return Cancelamento.INEXISTENTE;
        }
        if (usuario <= 0 || usuario != p.usuario() || pedidoAtual == null
                || pedidoAtual.equals(p.pedidoOrigem())) return Cancelamento.RECUSADA;
        pendente = null;
        return Cancelamento.CANCELADA;
    }

    /** Vazio = mensagem comum; presente = resposta deterministica sem passar pelo modelo. */
    public Optional<String> tratar(String texto, long usuario, String pedidoAtual) {
        String recebido = normalizar(texto);
        boolean fraseDeConfirmacao = switch (recebido) {
            case "confirmo", "confirmo a tarefa", "confirmo a operacao", "pode prosseguir",
                    "pode proceguir", "pode continuar", "pode seguir", "pode executar",
                    "sim pode prosseguir" -> true;
            default -> false;
        };
        if (!fraseDeConfirmacao && !recebido.matches(
                "confirmo(?: pc| rotina)? [0-9]{6}|cancelar|cancelo [0-9]{6}"))
            return Optional.empty();
        Pendente executar = null;
        synchronized (this) {
            Pendente p = pendente;
            if (p == null) return Optional.of("Nao ha confirmacao pendente. Nenhuma acao foi executada; "
                    + "se uma edicao falhou, releia o arquivo e prepare uma nova proposta.");
            if (usuario <= 0 || usuario != p.usuario() || pedidoAtual == null
                    || pedidoAtual.equals(p.pedidoOrigem()))
                return Optional.of("Confirmacao recusada: envie uma nova mensagem autenticada.");
            if (!relogio.instant().isBefore(p.expira())) {
                pendente = null;
                return Optional.of("A confirmacao expirou; faca um novo pedido.");
            }
            if (recebido.equals("cancelar") || recebido.equals("cancelo " + p.codigo())) {
                pendente = null;
                return Optional.of("Operacao do PC cancelada.");
            }
            String esperado = p.tipo().equals("rotina") ? "confirmo rotina " + p.codigo()
                    : "confirmo pc " + p.codigo();
            if (!fraseDeConfirmacao && !recebido.equals(esperado)
                    && !recebido.equals("confirmo " + p.codigo()))
                return Optional.of("Codigo incorreto; a operacao continua pendente ate expirar ou ser cancelada.");
            pendente = null;
            executar = p;
        }
        return Optional.of(executar.executar().get());
    }

    private static String normalizar(String texto) {
        if (texto == null) return "";
        String semAcentos = Normalizer.normalize(texto, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return semAcentos.toLowerCase(Locale.ROOT).trim()
                .replaceAll("[.!?]+$", "").replace(',', ' ')
                .replaceAll("\\s+", " ").trim();
    }
}
