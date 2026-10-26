package dev.jarvis.diario;

import java.time.Instant;

/**
 * Uma linha do diário: o que o Jarvis fez, quando e a pedido de quem.
 *
 * <p>A {@code descricao} é montada <b>em Java, de dado real</b> — o nome que o agente do PC
 * devolveu, o assunto que o {@code MemoriaService} gravou, o nome da rotina —, nunca texto escrito
 * pelo modelo. É a mesma regra da {@code pergunta} do {@code Aprovacoes}: deixar o modelo narrar o
 * diário seria deixá-lo escolher o que o dono lê sobre o que ele próprio fez.
 *
 * <p>Vem cortada em {@link #MAX_DESCRICAO} caracteres pela régua da {@code Ficha} de
 * {@code listar_memorias}, e pelo mesmo motivo: isto é lido em voz alta, e vinte linhas longas
 * estourariam o limite de contexto da resposta.
 *
 * <p>A {@code referencia} é o que permite <b>desfazer</b> a ação depois: o identificador que o lado
 * de lá devolveu — hoje o {@code idLixeira} de um {@code apagar_arquivo} —, vazia quando a ação não
 * tem volta ou não precisa de uma. Não é falada: quem vai ao ouvido do dono é a {@code descricao},
 * e um UUID lido em voz alta não ajuda ninguém. Como a descrição, é <b>dado real</b> de quem
 * executou, nunca texto escrito pelo modelo.
 */
public record AcaoRegistrada(Instant instante, TipoDeAcao tipo, String descricao,
        OrigemDaAcao origem, String referencia) {

    /** O corte da descrição. Um nome de arquivo com caminho longo caberia; um parágrafo, não. */
    public static final int MAX_DESCRICAO = 120;

    public AcaoRegistrada {
        if (instante == null) {
            throw new IllegalArgumentException("instante da ação necessário");
        }
        if (tipo == null) {
            throw new IllegalArgumentException("tipo da ação necessário");
        }
        descricao = encurtar(descricao);
        origem = origem == null ? OrigemDaAcao.DESCONHECIDA : origem;
        referencia = referencia == null ? "" : referencia.strip();
    }

    /** Ação sem nada para desfazer, que é a maioria. Mantém quem monta a linha com quatro campos. */
    public AcaoRegistrada(Instant instante, TipoDeAcao tipo, String descricao, OrigemDaAcao origem) {
        this(instante, tipo, descricao, origem, "");
    }

    private static String encurtar(String bruto) {
        if (bruto == null) {
            return "";
        }
        String limpo = bruto.strip().replaceAll("\\s+", " ");
        return limpo.length() <= MAX_DESCRICAO ? limpo
                : limpo.substring(0, MAX_DESCRICAO - 1).strip() + "…";
    }
}
