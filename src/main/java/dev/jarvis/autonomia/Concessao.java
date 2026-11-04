package dev.jarvis.autonomia;

import java.time.Instant;

/**
 * Uma permissão que o dono deu ao Jarvis para fazer uma ação do catálogo sem perguntar.
 *
 * <p>Quem grava é sempre o servidor ({@link Concessoes}), nunca o modelo: a temporária depois de
 * conferir a mensagem do dono, a aprendida depois de um "sim" a uma pendência própria. O
 * {@code config/autonomia.md} continua só leitura e continua mandando — uma concessão vale
 * <b>depois</b> dele e antes do padrão, e nunca abaixo do piso.
 *
 * @param alvo      o nome da rotina, por exemplo; nulo quando vale para a ação inteira
 * @param ate       o fim, só em {@link Escopo#ATE}
 * @param idTarefa  a tarefa dona da concessão, só em {@link Escopo#TAREFA}
 * @param evidencia de onde veio: o trecho literal da mensagem, ou o motivo montado em Java
 * @param usadaEm   a primeira vez que liberou uma ação; nulo enquanto não liberou nenhuma
 */
public record Concessao(String id, String chave, String alvo, Escopo escopo, Instant ate,
        String idTarefa, Origem origem, String evidencia, Instant criadaEm, Instant usadaEm) {

    /** Até quando vale. A {@link #chave()} é o que vai para o JSON. */
    public enum Escopo {
        ATE("ate"),
        PERMANENTE("permanente"),
        /** Recibo de um plano aprovado: cobre somente a tarefa identificada, nunca a conversa. */
        TAREFA("tarefa");

        private final String chave;

        Escopo(String chave) { this.chave = chave; }

        public String chave() { return chave; }

        static Escopo porChave(String chave) {
            for (Escopo e : values()) if (e.chave.equals(chave)) return e;
            return null;
        }
    }

    /** Como nasceu. A {@link #chave()} é o que vai para o JSON. */
    public enum Origem {
        TEMPORARIA("temporaria"),
        APRENDIDA("aprendida"),
        PLANO("plano");

        private final String chave;

        Origem(String chave) { this.chave = chave; }

        public String chave() { return chave; }

        static Origem porChave(String chave) {
            for (Origem o : values()) if (o.chave.equals(chave)) return o;
            return null;
        }
    }

    /** A ação do catálogo. Nunca nula numa concessão carregada: a leitura descarta chave desconhecida. */
    public AcaoAutonoma acao() {
        return AcaoAutonoma.porChave(chave);
    }

    /** O prazo passou. Permanente e de tarefa nunca vencem pelo relógio. */
    public boolean vencida(Instant agora) {
        return escopo == Escopo.ATE && !agora.isBefore(ate);
    }

    /** Libera a ação numa conversa agora: não venceu e não é de tarefa. */
    public boolean valeNaConversa(Instant agora) {
        return escopo != Escopo.TAREFA && !vencida(agora);
    }

    /** Se cobre aquela ação e aquele alvo. Concessão sem alvo cobre todos os alvos da ação. */
    public boolean cobre(AcaoAutonoma acao, String alvoPedido) {
        if (!acao.chave().equals(chave)) return false;
        return alvo == null || alvo.equals(alvoPedido);
    }

    /** O que ela libera, em português ("rodar a rotina backup_notas"). */
    public String oQue() {
        AcaoAutonoma acao = acao();
        return acao == null ? chave : acao.oQue(alvo);
    }

    Concessao usadaAgora(Instant agora) {
        return new Concessao(id, chave, alvo, escopo, ate, idTarefa, origem, evidencia, criadaEm, agora);
    }
}
