package dev.jarvis.conversa;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/** Retratos imutáveis e rápidos de ler, separados do bloqueio das chamadas ao modelo. */
@Service
public class RegistroConversas {
    public record Pedido(String id, String canal, String texto, String estado, String iniciadoEm) { }
    public record Troca(String id, String canal, String pedido, RespostaConversa resposta) { }
    public record Historico(String sessao, long revisao, List<Troca> trocas,
            Pedido emAndamento, RespostaConversa ultima) { }
    public record Progresso(String id, String canal, String nome, String descricao,
            Integer passo, Integer total) { }
    private static class Conversa {
        long revisao;
        List<Troca> trocas = List.of();
        Pedido pendente;
        RespostaConversa ultima;
        boolean limpando;
    }
    private final String sessao = UUID.randomUUID().toString();
    private final Map<Long, Conversa> conversas = new HashMap<>();
    private final ApplicationEventPublisher eventos;

    public RegistroConversas(ApplicationEventPublisher eventos) { this.eventos = eventos; }

    public synchronized Historico historico(long chatId) {
        Conversa c = conversa(chatId);
        return new Historico(sessao, c.revisao, c.trocas, c.pendente, c.ultima);
    }

    public Pedido reservar(long chatId, String texto) {
        Pedido pedido;
        synchronized (this) {
            Conversa c = conversa(chatId);
            if (c.pendente != null || c.limpando) throw new OcupadaException();
            pedido = new Pedido(UUID.randomUUID().toString(), "hud", texto, "aguardando", Instant.now().toString());
            c.pendente = pedido;
            c.revisao++;
        }
        publicar(chatId, "conversa.pensando", pedido);
        return pedido;
    }

    public synchronized boolean pendente(long chatId, String id) {
        Pedido pedido = conversa(chatId).pendente;
        return pedido != null && pedido.id().equals(id);
    }

    public void processando(long chatId, String id) {
        Pedido pedido;
        synchronized (this) {
            Conversa c = conversa(chatId);
            if (!pendente(chatId, id)) return;
            pedido = new Pedido(id, "hud", c.pendente.texto(), "processando", c.pendente.iniciadoEm());
            c.pendente = pedido;
            c.revisao++;
        }
        publicar(chatId, "conversa.pensando", pedido);
    }

    public void concluir(long chatId, String pedido, RespostaConversa resposta) {
        synchronized (this) {
            Conversa c = conversa(chatId);
            List<Troca> novas = new ArrayList<>(c.trocas);
            novas.add(new Troca(resposta.id(), resposta.canal(), pedido, resposta));
            c.trocas = List.copyOf(novas.subList(Math.max(0, novas.size() - 10), novas.size()));
            if ("hud".equals(resposta.canal())) {
                c.ultima = resposta;
                c.pendente = null;
            }
            c.revisao++;
        }
        if ("hud".equals(resposta.canal())) publicar(chatId, "conversa.resposta", resposta);
        avisarHistorico(chatId);
    }

    /** Insere um resultado posterior sem encerrar uma conversa nova que esteja em andamento. */
    public void registrarResultadoTardio(long chatId, String id, String texto) {
        registrarResultadoTardio(chatId, id, texto, List.of());
    }

    public void registrarResultadoTardio(long chatId, String id, String texto, List<Anexo> anexos) {
        RespostaConversa resposta = new RespostaConversa(id, "hud", texto, anexos, null,
                Instant.now().toString(), null);
        synchronized (this) {
            Conversa c = conversa(chatId);
            List<Troca> novas = new ArrayList<>(c.trocas);
            novas.add(new Troca(id, "hud", "Resultado da operação " + id, resposta));
            c.trocas = List.copyOf(novas.subList(Math.max(0, novas.size() - 10), novas.size()));
            if (c.pendente == null) c.ultima = resposta;
            c.revisao++;
        }
        publicar(chatId, "conversa.resposta", resposta);
        avisarHistorico(chatId);
    }

    /** Confirma exclusão sem reter o texto que pode conter o dado esquecido. */
    public void concluirSemHistorico(long chatId, RespostaConversa resposta) {
        synchronized (this) {
            Conversa c = conversa(chatId);
            c.trocas = List.of();
            c.pendente = null;
            c.ultima = resposta;
            c.revisao++;
        }
        if ("hud".equals(resposta.canal())) publicar(chatId, "conversa.resposta", resposta);
        avisarHistorico(chatId);
    }

    public void falhar(long chatId, String id) {
        falhar(chatId, id, "Não consegui concluir este pedido. Tente novamente.");
    }

    public void falhar(long chatId, String id, String motivo) {
        RespostaConversa resposta;
        synchronized (this) {
            if (!pendente(chatId, id)) return;
            Conversa c = conversa(chatId);
            resposta = new RespostaConversa(id, "hud", "", List.of(), null,
                    Instant.now().toString(), motivo);
            c.ultima = resposta;
            c.pendente = null;
            c.revisao++;
        }
        publicar(chatId, "conversa.erro", resposta);
        avisarHistorico(chatId);
    }

    public synchronized void reservarLimpeza(long chatId) {
        Conversa c = conversa(chatId);
        if (c.pendente != null || c.limpando) throw new OcupadaException();
        c.limpando = true;
    }

    public void limpar(long chatId) {
        synchronized (this) {
            Conversa c = conversa(chatId);
            c.trocas = List.of();
            c.pendente = null;
            c.ultima = null;
            c.limpando = false;
            c.revisao++;
        }
        avisarHistorico(chatId);
    }

    public void progresso(long chatId, Progresso progresso) {
        if (pendente(chatId, progresso.id())) publicar(chatId, "conversa.ferramenta", progresso);
    }

    private Conversa conversa(long chatId) { return conversas.computeIfAbsent(chatId, id -> new Conversa()); }
    private void avisarHistorico(long chatId) {
        Historico h = historico(chatId);
        publicar(chatId, "conversa.historico", Map.of("sessao", h.sessao(), "revisao", h.revisao()));
    }
    private void publicar(long chatId, String nome, Object dados) {
        OuvinteProgresso.avisar(() -> eventos.publishEvent(new EventoConversa(chatId, nome, dados)));
    }
    public static class OcupadaException extends RuntimeException { }
}
