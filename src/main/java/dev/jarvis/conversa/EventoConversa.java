package dev.jarvis.conversa;

/** Evento de domínio, traduzido para SSE exclusivamente pela camada web. */
public record EventoConversa(long chatId, String nome, Object dados) { }
