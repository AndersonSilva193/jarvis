package dev.jarvis.conversa;

/** Contrato para ferramentas futuras; a ferramenta continua responsável por validar o código. */
public record Confirmacao(String codigo, String descricao, String expiraEm) { }
