package dev.jarvis.conversa;

/** Referência pública: nunca contém caminho local nem credenciais. */
public record Anexo(String id, String tipo, String nome, String mime, String url, String expiraEm) { }
